package com.gwgs.akkaagentic.approvals.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure unit tests for the approval FSM and the decision guard — no Akka runtime, no mocked model, no
  * HTTP. Asserts every row of the state table and the decision truth table in specs/007 data-model §3.
  *
  * These are the tests that make the integration test (which must poll a real task chain) small: the
  * combinatorics live here, and the integration test only has to prove the wiring.
  */
class ApprovalCaseTest:

  private val someDraft = TaskOutcome.Done("You can request a refund within 30 days.")
  private val gateOpen = TaskOutcome.Pending
  private val notStarted = TaskOutcome.Pending

  // --- state machine ---------------------------------------------------------------------------

  @Test
  def unknownCaseWhenDraftTaskMissing(): Unit =
    assertThat(ApprovalCase.progress(TaskOutcome.Missing, TaskOutcome.Missing, TaskOutcome.Missing))
      .isEqualTo(CaseProgress.NotFound)

  @Test
  def draftingWhileDraftPendingOrRunning(): Unit =
    assertThat(ApprovalCase.progress(TaskOutcome.Pending, notStarted, notStarted))
      .isEqualTo(CaseProgress.Drafting)
    assertThat(ApprovalCase.progress(TaskOutcome.Running, notStarted, notStarted))
      .isEqualTo(CaseProgress.Drafting)

  @Test
  def draftFailedCarriesReasonAndNeverOpensGate(): Unit =
    assertThat(ApprovalCase.progress(TaskOutcome.Failed(Some("no sources")), notStarted, notStarted))
      .isEqualTo(CaseProgress.DraftFailed("no sources"))

  @Test
  def draftFailedFallsBackToAGenericReason(): Unit =
    assertThat(ApprovalCase.progress(TaskOutcome.Failed(None), notStarted, notStarted))
      .isEqualTo(CaseProgress.DraftFailed("the draft could not be produced"))

  /** The heart of the capability: a completed draft with an undecided gate shows the draft and
    * nothing else — there is no reply to show, because publishing has not been released (SC-003).
    */
  @Test
  def awaitingApprovalOnceDraftCompletesAndGateUndecided(): Unit =
    assertThat(ApprovalCase.progress(someDraft, gateOpen, notStarted))
      .isEqualTo(CaseProgress.AwaitingApproval("You can request a refund within 30 days."))

  @Test
  def publishingAfterApprovalWhilePublishRuns(): Unit =
    assertThat(ApprovalCase.progress(someDraft, TaskOutcome.Done(()), TaskOutcome.Running))
      .isEqualTo(CaseProgress.Publishing("You can request a refund within 30 days."))

  @Test
  def publishedOnceApprovedPublishCompletes(): Unit =
    assertThat(ApprovalCase.progress(someDraft, TaskOutcome.Done(()), TaskOutcome.Done("Final reply.")))
      .isEqualTo(CaseProgress.Published("Final reply."))

  @Test
  def rejectedCarriesTheReviewersNote(): Unit =
    assertThat(ApprovalCase.progress(someDraft, TaskOutcome.Failed(Some("too casual")), TaskOutcome.Failed(None)))
      .isEqualTo(CaseProgress.Rejected(Some("too casual")))

  @Test
  def rejectedWithoutANote(): Unit =
    assertThat(ApprovalCase.progress(someDraft, TaskOutcome.Failed(None), TaskOutcome.Failed(None)))
      .isEqualTo(CaseProgress.Rejected(None))

  @Test
  def publishFailedIsDistinctFromPublishing(): Unit =
    assertThat(ApprovalCase.progress(someDraft, TaskOutcome.Done(()), TaskOutcome.Failed(Some("boom"))))
      .isEqualTo(CaseProgress.PublishFailed("You can request a refund within 30 days.", "boom"))

  /** A rejected case must never report a reply, even if a publish result somehow existed. */
  @Test
  def rejectionWinsOverAnyPublishOutcome(): Unit =
    assertThat(ApprovalCase.progress(someDraft, TaskOutcome.Failed(Some("no")), TaskOutcome.Done("leaked!")))
      .isEqualTo(CaseProgress.Rejected(Some("no")))

  @Test
  def everyStateHasADistinctLabel(): Unit =
    val labels = List(
      CaseProgress.NotFound,
      CaseProgress.Drafting,
      CaseProgress.DraftFailed("r"),
      CaseProgress.AwaitingApproval("d"),
      CaseProgress.Publishing("d"),
      CaseProgress.Published("r"),
      CaseProgress.Rejected(None),
      CaseProgress.PublishFailed("d", "r")
    ).map(_.label)
    assertThat(labels.distinct.size).isEqualTo(labels.size)

  // --- decision guard (research R4 truth table) ------------------------------------------------

  @Test
  def decisionOnUnknownCaseIsNotFound(): Unit =
    assertThat(ApprovalCase.decide(TaskOutcome.Missing, TaskOutcome.Missing)).isEqualTo(Decision.NotFound)
    assertThat(ApprovalCase.decide(someDraft, TaskOutcome.Missing)).isEqualTo(Decision.NotFound)

  @Test
  def decisionBeforeDraftCompletesIsNotAwaiting(): Unit =
    assertThat(ApprovalCase.decide(TaskOutcome.Pending, gateOpen)).isEqualTo(Decision.NotAwaiting)
    assertThat(ApprovalCase.decide(TaskOutcome.Running, gateOpen)).isEqualTo(Decision.NotAwaiting)

  @Test
  def decisionAfterDraftFailedIsNotAwaiting(): Unit =
    assertThat(ApprovalCase.decide(TaskOutcome.Failed(Some("x")), TaskOutcome.Failed(None)))
      .isEqualTo(Decision.NotAwaiting)

  @Test
  def decisionAllowedOnlyWhenDraftDoneAndGatePending(): Unit =
    assertThat(ApprovalCase.decide(someDraft, TaskOutcome.Pending)).isEqualTo(Decision.Open)

  @Test
  def secondDecisionAfterApprovalIsAlreadyDecided(): Unit =
    assertThat(ApprovalCase.decide(someDraft, TaskOutcome.Done(()))).isEqualTo(Decision.AlreadyDecided)

  @Test
  def secondDecisionAfterRejectionIsAlreadyDecided(): Unit =
    assertThat(ApprovalCase.decide(someDraft, TaskOutcome.Failed(Some("no")))).isEqualTo(Decision.AlreadyDecided)

  /** A decision arriving while another is mid-flight (gate assigned, not yet completed) must not slip
    * through as a second decision.
    */
  @Test
  def decisionWhileAnotherIsInFlightIsAlreadyDecided(): Unit =
    assertThat(ApprovalCase.decide(someDraft, TaskOutcome.Running)).isEqualTo(Decision.AlreadyDecided)
