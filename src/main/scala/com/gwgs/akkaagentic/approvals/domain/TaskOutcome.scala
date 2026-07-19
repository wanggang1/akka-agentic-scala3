package com.gwgs.akkaagentic.approvals.domain

/** What happened to one task of an approval case, expressed without any Akka type.
  *
  * The SDK's `TaskStatus` has seven values and its snapshot carries `Optional` result / failure fields;
  * the approval FSM only cares about five distinguishable outcomes. Collapsing the SDK shape into this
  * ADT at the edge is what lets [[ApprovalCase]] be pure domain logic — unit-testable with no runtime,
  * no mocked model, and no HTTP.
  *
  * `Pending` and `Running` are kept apart on purpose: the human gate is only decidable while it is
  * strictly `Pending`, because `Running` (assigned) means a decision is already in flight.
  */
enum TaskOutcome[+A]:

  /** The task was never created — for the draft task, this means the case handle is unknown. */
  case Missing

  /** Created and waiting: either for its dependencies, or (for the gate) for a human. */
  case Pending

  /** Claimed and under way. */
  case Running

  /** Finished successfully, carrying whatever the FSM needs from its typed result. */
  case Done[+A](value: A) extends TaskOutcome[A]

  /** Terminally failed, cancelled, or rejected by a task rule, with the reason when one was given. */
  case Failed(reason: Option[String])
