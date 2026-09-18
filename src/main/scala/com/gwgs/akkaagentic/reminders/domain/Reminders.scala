package com.gwgs.akkaagentic.reminders.domain

import java.time.Instant

/** Every reminder this process knows about, as **one immutable value**.
  *
  * All the rules live here, as pure functions of the shape `Reminders => (Reminders, Result)`: each
  * returns the next value and what happened, and never touches the one it was called on. That keeps
  * the rules testable with no threads and no runtime, and it is what lets the application layer make
  * every transition atomic with a single compare-and-set (see `ReminderStore`).
  *
  * **Terminal wins.** A transition out of a terminal state is a no-op that reports it did not happen,
  * so whichever of fire / cancel / fail commits first is the one that stands. That is how the
  * fire-versus-cancel window is decided: at the commit, not by timing luck.
  *
  * **No Akka import** (Constitution II).
  */
final case class Reminders(byId: Map[String, Reminder] = Map.empty):

  def get(id: String): Option[Reminder] = byId.get(id)

  def record(id: String, note: String, at: Instant): (Reminders, Reminder) =
    val pending = Reminder.pending(id, note, at)
    (copy(byId = byId.updated(id, pending)), pending)

  /** `None` means it did not fire: unknown, or already terminal (e.g. cancelled first). */
  def fire(id: String, at: Instant): (Reminders, Option[Reminder]) = settle(id)(_.fired(at))

  /** Exhausted retries (FR-008). `None` means it was unknown or already terminal. */
  def fail(id: String, reason: String): (Reminders, Option[Reminder]) = settle(id)(_.failed(reason))

  /** A firing attempt that did not end the reminder — counted, so a bound is shown by count (SC-006). */
  def attempt(id: String): (Reminders, Option[Reminder]) = settle(id)(_.attempted)

  def cancel(id: String, at: Instant): (Reminders, CancelOutcome) =
    byId.get(id) match
      case None                                => (this, CancelOutcome.Unknown)
      case Some(done) if done.state.isTerminal => (this, CancelOutcome.AlreadyTerminal(done))
      case Some(pending) =>
        val cancelled = pending.cancelled(at)
        (copy(byId = byId.updated(id, cancelled)), CancelOutcome.Cancelled(cancelled))

  /** Apply `change` to a still-pending reminder; leave everything else exactly as it is. */
  private def settle(id: String)(change: Reminder => Reminder): (Reminders, Option[Reminder]) =
    byId.get(id).filterNot(_.state.isTerminal) match
      case Some(pending) =>
        val next = change(pending)
        (copy(byId = byId.updated(id, next)), Some(next))
      case None => (this, None)
