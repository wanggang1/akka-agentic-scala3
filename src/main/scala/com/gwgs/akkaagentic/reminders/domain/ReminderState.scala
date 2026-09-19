package com.gwgs.akkaagentic.reminders.domain

/** The four states a caller can tell apart (FR-003), and the rule that keeps them honest.
  *
  * Collapsing any two of these would mislead: a reminder that failed every permitted attempt has not
  * "fired", and one cancelled after it already fired was never cancelled at all (FR-006). That is why
  * this is four cases rather than a boolean plus a timestamp.
  *
  * **No Akka import** (Constitution II) — this is pure data, unit-tested with no runtime.
  */
enum ReminderState:
  case Pending, Fired, Cancelled, Failed

  /** Everything except `Pending` is final: a reminder that has fired, been cancelled or failed never
    * moves again. This is what makes "cancel" answerable with the state it is actually in rather than
    * with a success it did not have.
    */
  def isTerminal: Boolean = this != ReminderState.Pending

  /** Legal transitions: only out of `Pending`, and only into a terminal state. */
  def canTransitionTo(next: ReminderState): Boolean =
    this == ReminderState.Pending && next.isTerminal

  /** The wire spelling used by the HTTP contract (`"pending"`, `"fired"`, …). Defined here, beside
    * the states themselves, so the API cannot invent a fifth name for one of them. */
  def label: String = toString.toLowerCase
