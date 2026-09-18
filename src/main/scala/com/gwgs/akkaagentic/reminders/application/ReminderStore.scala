package com.gwgs.akkaagentic.reminders.application

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

import com.gwgs.akkaagentic.reminders.domain.{Reminder, ReminderState}

/** Where a reminder's state lives — in this process, and only for as long as it runs.
  *
  * **That is a decision, not an oversight, and it was measured.** A pending timer does not survive a
  * service restart in local dev mode: a 60 s timer was scheduled, the service killed 10 s later with
  * the on-disk store enabled, restarted, and watched for 100 s past its due time — it never fired
  * (specs/017 research Q-C). Durable reminder *state* on top of a non-durable *timer* would leave a
  * caller reading `pending` for ever for something that can no longer happen, which is a worse answer
  * than `404`. So the state's lifetime is matched to the timer's, and `GET` after a restart honestly
  * reports that the reminder is gone.
  *
  * It also keeps the Java quarantine at one class: a `KeyValueEntity` would have needed a method-ref
  * caller of its own (the capability-6 shape, README §8) *and* introduced the inconsistency above.
  *
  * **An `object`, like the probe's `ReminderLog` before it.** The runtime constructs endpoints and
  * timed actions per call, so per-instance state would record nothing a later request could read.
  *
  * Every transition goes through `ConcurrentHashMap.compute`, so it is applied atomically to whatever
  * the reminder's state is at that instant. That is what makes the fire-versus-cancel window
  * decidable rather than a race: whichever transition reaches `compute` first wins, and the loser is
  * told what actually happened instead of overwriting it.
  */
object ReminderStore:

  /** What a cancellation actually did — three outcomes the HTTP layer must keep distinct (FR-006). */
  enum CancelOutcome:
    /** It was pending; it is now cancelled and will not fire. */
    case Cancelled(reminder: Reminder)

    /** Nothing to cancel: it had already fired, failed, or been cancelled. Carries the real state, so
      * the caller is told what happened rather than given a success it did not have. */
    case AlreadyTerminal(reminder: Reminder)

    /** No such handle. */
    case Unknown

  private val reminders = ConcurrentHashMap[String, Reminder]()

  /** Record a newly scheduled reminder as pending. The delay is taken for the caller's benefit only —
    * the runtime owns the actual timer — so it is logged into the record rather than enforced here. */
  def record(id: String, note: String, delay: FiniteDuration, at: Instant = Instant.now()): Reminder =
    val pending = Reminder.pending(id, note, at)
    reminders.put(id, pending)
    pending

  /** Mark a reminder fired. Returns the resulting reminder, or `None` if the id is unknown or the
    * reminder had already reached a terminal state — the cancel-while-firing case, where the cancel
    * got there first and must not be overwritten. */
  def markFired(id: String, at: Instant = Instant.now()): Option[Reminder] =
    transition(id)(_.fired(at))

  /** Mark a reminder failed, after its permitted attempts are exhausted (FR-008). */
  def markFailed(id: String, reason: String): Option[Reminder] =
    transition(id)(_.failed(reason))

  /** Count an attempt that did not end the reminder, so a bounded retry can be shown by count. */
  def recordAttempt(id: String): Option[Reminder] =
    Option(reminders.computeIfPresent(id, (_, current) => current.attempted))

  /** Cancel a pending reminder.
    *
    * The decision and the outcome are computed **inside** `compute`, so the answer describes the state
    * the reminder was actually in at the moment of the write. Deciding first and writing second would
    * reintroduce exactly the race FR-006 is about: two callers could both be told they cancelled it.
    */
  def cancel(id: String, at: Instant = Instant.now()): CancelOutcome =
    var outcome: CancelOutcome = CancelOutcome.Unknown
    reminders.computeIfPresent(
      id,
      (_, current) =>
        if current.state.canTransitionTo(ReminderState.Cancelled) then
          val justCancelled = current.cancelled(at)
          outcome = CancelOutcome.Cancelled(justCancelled)
          justCancelled
        else
          outcome = CancelOutcome.AlreadyTerminal(current)
          current)
    outcome

  def get(id: String): Option[Reminder] = Option(reminders.get(id))

  def ids: Set[String] = reminders.keySet.asScala.toSet

  /** Test hook: the store outlives individual requests by design, so a test that asserts on absence
    * has to be able to start from empty. */
  def clear(): Unit = reminders.clear()

  /** Apply a terminal transition atomically.
    *
    * `None` means the transition did **not** happen — either the id is unknown, or the reminder had
    * already reached a terminal state and the first writer keeps it. A caller that wants to know which
    * of those it was can ask [[get]]; the distinction only matters to the HTTP layer for cancellation,
    * which has its own three-way answer above.
    */
  private def transition(id: String)(change: Reminder => Reminder): Option[Reminder] =
    var applied: Option[Reminder] = None
    reminders.computeIfPresent(
      id,
      (_, current) =>
        if current.state.isTerminal then current
        else
          val next = change(current)
          applied = Some(next)
          next)
    applied
