package com.gwgs.akkaagentic.reminders.domain

import java.time.Instant

/** What a scheduled reminder *is*: the handle, the caller's note, where it got to, and when.
  *
  * Immutable, with `with*` copies — the store swaps whole values rather than mutating fields, which
  * is what lets a transition be applied atomically (see `ReminderStore`).
  *
  * The timestamps are deliberately separate rather than one `terminalAt`: a reader of
  * `GET /reminders/{id}` should not have to consult `state` to learn what a single timestamp means.
  */
final case class Reminder(
    id: String,
    note: String,
    state: ReminderState,
    scheduledAt: Instant,
    firedAt: Option[Instant] = None,
    cancelledAt: Option[Instant] = None,
    failure: Option[String] = None,
    attempts: Int = 0):

  def fired(at: Instant): Reminder =
    copy(state = ReminderState.Fired, firedAt = Some(at), attempts = attempts + 1)

  def cancelled(at: Instant): Reminder =
    copy(state = ReminderState.Cancelled, cancelledAt = Some(at))

  def failed(reason: String): Reminder =
    copy(state = ReminderState.Failed, failure = Some(reason), attempts = attempts + 1)

  /** A firing attempt that is not yet the last one — counted so FR-008's bound can be shown by count
    * rather than inferred (SC-006). */
  def attempted: Reminder = copy(attempts = attempts + 1)

object Reminder:

  def pending(id: String, note: String, at: Instant): Reminder =
    Reminder(id = id, note = note, state = ReminderState.Pending, scheduledAt = at)
