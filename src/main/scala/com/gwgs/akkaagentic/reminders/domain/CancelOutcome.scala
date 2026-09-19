package com.gwgs.akkaagentic.reminders.domain

/** What a cancellation actually did — three outcomes the HTTP layer must keep distinct (FR-006). */
enum CancelOutcome:
  /** It was pending; it is now cancelled and will not fire. */
  case Cancelled(reminder: Reminder)

  /** Nothing to cancel: it had already fired, failed, or been cancelled. Carries the real state, so the
    * caller is told what happened rather than given a success it did not have. */
  case AlreadyTerminal(reminder: Reminder)

  /** No such handle. */
  case Unknown
