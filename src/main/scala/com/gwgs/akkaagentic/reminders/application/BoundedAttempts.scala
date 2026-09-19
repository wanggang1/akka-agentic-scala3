package com.gwgs.akkaagentic.reminders.application

import scala.util.{Failure, Success, Try}

/** How every timed action in this capability runs its work: with a bound it enforces **itself**.
  *
  * Two measured facts make this necessary (specs/017 research Q-D; `docs/sdk-3.6.0-limitations.md` §7c):
  * a timer that exhausts its `maxRetries` stops **silently**, and the SDK gives a timed action **no
  * attempt number**. Bounded-and-silent would leave a reminder reading `pending` for ever. So the
  * attempt count lives in the store, and on the last permitted attempt the failure is recorded and the
  * action stops cleanly instead of asking the runtime for another try. The timer's `maxRetries` stays
  * as a backstop, set to the same bound.
  *
  * Shared on purpose: `ReminderAction` (production) and `FailingReminderAction` (the FR-008 instrument)
  * both run through here, so the bound `BoundedRetryIntegrationTest` proves against the instrument is
  * the production code path — not a copy of it.
  */
object BoundedAttempts:

  enum Outcome:
    /** The work completed. */
    case Succeeded

    /** It failed, and attempts remain: the caller should rethrow `cause` so the runtime retries. */
    case WillRetry(attempt: Int, cause: Throwable)

    /** It failed on its last permitted attempt: the reminder is now `failed`, and the caller should
      * return `done()` — a further retry would only repeat a decision already recorded. */
    case GaveUp(attempt: Int, cause: Throwable)

  /** Run one attempt of a reminder's work under a bound of `limit` attempts.
    *
    * The attempt number is read *before* the work runs, from the store's count of attempts already
    * made. Only non-fatal failures are caught (`Try`), so a fatal JVM error is never mistaken for a
    * failed reminder.
    */
  def run(reminderId: String, limit: Int)(work: => Unit): Outcome =
    val attempt = ReminderStore.get(reminderId).map(_.attempts + 1).getOrElse(1)
    Try(work) match
      case Success(_) => Outcome.Succeeded
      case Failure(cause) if attempt >= limit =>
        ReminderStore.markFailed(reminderId, s"failed on all $limit permitted attempts: ${cause.getMessage}")
        Outcome.GaveUp(attempt, cause)
      case Failure(cause) =>
        ReminderStore.recordAttempt(reminderId)
        Outcome.WillRetry(attempt, cause)
