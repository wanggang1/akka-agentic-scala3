package com.gwgs.akkaagentic.reminders.application

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec

import com.gwgs.akkaagentic.reminders.domain.{CancelOutcome, Reminder, Reminders}

/** Where a reminder's state lives — in this process, and only for as long as it runs.
  *
  * **The lifetime is a decision, and it was measured.** A pending timer does not survive a service
  * restart in local dev mode: a 60 s timer was scheduled, the service killed 10 s later with the
  * on-disk store enabled, restarted, and watched for 100 s past its due time — it never fired (specs/017
  * research Q-C). Durable reminder *state* on top of a non-durable *timer* would leave a caller reading
  * `pending` for ever for something that can no longer happen, which is a worse answer than `404`. It
  * also keeps the Java quarantine at one class: a `KeyValueEntity` would need a method-ref caller of its
  * own (README §8).
  *
  * **Why an `object`, and what that does and does not buy.** The runtime constructs endpoints and timed
  * actions per call, so per-instance state would record nothing a later request could read. That does
  * *not* make anything thread-safe — it is the opposite: this object is shared by HTTP request threads,
  * timer firings on the runtime's dispatcher, and tests, all at once.
  *
  * **So the concurrency argument is made in one place.** The only mutable thing in the capability is
  * the single cell below. Everything it holds is immutable, and every change is a pure
  * `Reminders => (Reminders, Result)` function (the rules, in the domain) committed by one
  * compare-and-set. Reasoning about concurrency reduces to: *a transition either commits against the
  * value it read, or retries against the new one.* No two callers can both be told they cancelled the
  * same reminder, because only one CAS can win.
  *
  * Because a transition can be retried under contention, it must be pure — which is why the timestamp
  * is taken **once, before** `modify`, rather than inside the transition.
  */
object ReminderStore:

  private val cell = AtomicReference(Reminders())

  def record(id: String, note: String, at: Instant = Instant.now()): Reminder =
    modify(_.record(id, note, at))

  def markFired(id: String, at: Instant = Instant.now()): Option[Reminder] = modify(_.fire(id, at))

  def markFailed(id: String, reason: String): Option[Reminder] = modify(_.fail(id, reason))

  def recordAttempt(id: String): Option[Reminder] = modify(_.attempt(id))

  def cancel(id: String, at: Instant = Instant.now()): CancelOutcome = modify(_.cancel(id, at))

  def get(id: String): Option[Reminder] = cell.get().get(id)

  def ids: Set[String] = cell.get().byId.keySet

  /** Test hook: the store outlives individual requests by design, so a test that asserts on absence has
    * to be able to start from empty. */
  def clear(): Unit = cell.set(Reminders())

  /** Commit a pure transition atomically: read, compute, compare-and-set; on a lost race, redo it
    * against the value that won. `compareAndSet` compares by reference, which is exactly right for
    * immutable values — and a no-op transition that returns the same value commits trivially. */
  @tailrec
  private def modify[B](transition: Reminders => (Reminders, B)): B =
    val current = cell.get()
    val (next, result) = transition(current)
    if cell.compareAndSet(current, next) then result else modify(transition)
