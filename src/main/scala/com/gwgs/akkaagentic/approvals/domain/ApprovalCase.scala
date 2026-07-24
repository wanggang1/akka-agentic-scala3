package com.gwgs.akkaagentic.approvals.domain

/** Where a case has got to, derived from its three tasks. The `label` is the vocabulary the spec uses
  * and the endpoint puts on the wire.
  */
enum CaseProgress(val label: String):

  /** No such case — the draft task was never created. */
  case NotFound extends CaseProgress("not-found")

  /** The agent is still writing the draft; the gate has not opened. */
  case Drafting extends CaseProgress("drafting")

  /** The draft was abandoned, so the gate never opens (terminal). */
  case DraftFailed(reason: String) extends CaseProgress("draft-failed")

  /** **The gate is open**: a draft exists and a human must now decide. Nothing is published. */
  case AwaitingApproval(draft: String) extends CaseProgress("awaiting-approval")

  /** Approved; the publish agent is running (a brief transient). */
  case Publishing(draft: String) extends CaseProgress("publishing")

  /** Approved and published (terminal). */
  case Published(reply: String) extends CaseProgress("published")

  /** Rejected by the human; the publish task was cancelled, so nothing is ever published (terminal). */
  case Rejected(note: Option[String]) extends CaseProgress("rejected")

  /** Approved, but publishing itself failed (terminal). Beyond the spec's minimum, but reporting it
    * honestly beats polling "publishing" forever.
    */
  case PublishFailed(draft: String, reason: String) extends CaseProgress("publish-failed")

/** Whether a human decision (approve or reject) may be applied right now. */
enum Decision:

  /** The gate is genuinely open — apply the decision. */
  case Open

  /** Unknown case handle. */
  case NotFound

  /** Still drafting, or the draft failed — the gate never opened. */
  case NotAwaiting

  /** Already approved or rejected — a repeat is a safe no-op, never a second publish. */
  case AlreadyDecided

/** The approval case's state machine and decision rule, as pure functions.
  *
  * This is the capability's business logic, deliberately kept out of the endpoint: the endpoint adapts
  * SDK snapshots into [[TaskOutcome]]s, calls these two functions, and renders the answer. Keeping it
  * here means every row of the spec's state table and decision truth table is asserted by fast unit
  * tests, with no runtime, no mocked model and no polling (specs/007 data-model §3).
  *
  * Note what is *not* here: nothing sequences the work. The gate is enforced by the task dependency
  * graph in the runtime (FR-007); these functions only *observe* and *classify*.
  */
object ApprovalCase:

  private val DraftFailedFallback = "the draft could not be produced"
  private val PublishFailedFallback = "the reply could not be published"

  /** The state machine: three task outcomes in, one case state out. Total — every combination maps. */
  def progress(
      draft: TaskOutcome[String],
      gate: TaskOutcome[Unit],
      publish: TaskOutcome[String]
  ): CaseProgress =
    draft match
      case TaskOutcome.Missing => CaseProgress.NotFound
      case TaskOutcome.Pending | TaskOutcome.Running => CaseProgress.Drafting
      case TaskOutcome.Failed(reason) => CaseProgress.DraftFailed(reason.getOrElse(DraftFailedFallback))
      case TaskOutcome.Done(body) => afterDraft(body, gate, publish)

  private def afterDraft(
      draft: String,
      gate: TaskOutcome[Unit],
      publish: TaskOutcome[String]
  ): CaseProgress =
    gate match
      // A rejection is recorded as a *failed* gate, with the reviewer's note as the reason.
      case TaskOutcome.Failed(note) => CaseProgress.Rejected(note)
      case TaskOutcome.Done(_) => afterApproval(draft, publish)
      case _ => CaseProgress.AwaitingApproval(draft)

  private def afterApproval(draft: String, publish: TaskOutcome[String]): CaseProgress =
    publish match
      case TaskOutcome.Done(reply) => CaseProgress.Published(reply)
      case TaskOutcome.Failed(reason) =>
        CaseProgress.PublishFailed(draft, reason.getOrElse(PublishFailedFallback))
      case _ => CaseProgress.Publishing(draft)

  /** The decision guard (specs/007 research R4): a decision applies only when the draft has completed
    * **and** the gate is still strictly pending. Guarding on observed state — rather than trusting
    * `complete`/`fail` to be idempotent — is what makes a premature decision and a repeated decision
    * two *distinct*, deterministic refusals (FR-008, FR-009).
    */
  def decide(draft: TaskOutcome[String], gate: TaskOutcome[Unit]): Decision =
    (draft, gate) match
      case (TaskOutcome.Missing, _) => Decision.NotFound
      case (_, TaskOutcome.Missing) => Decision.NotFound
      case (TaskOutcome.Done(_), TaskOutcome.Pending) => Decision.Open
      case (TaskOutcome.Done(_), _) => Decision.AlreadyDecided
      case _ => Decision.NotAwaiting
