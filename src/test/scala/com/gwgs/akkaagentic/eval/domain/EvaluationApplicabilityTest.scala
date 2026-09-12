package com.gwgs.akkaagentic.eval.domain

import com.gwgs.akkaagentic.eval.domain.EvaluationApplicability.Applicability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T006/T022 — the applicability rules, unit-tested with no runtime and no model.
  *
  * The prefixes are passed in rather than imported, so these tests use stand-in constants: the domain
  * must work for *any* refusal and failure markers, not just capability 8's.
  */
class EvaluationApplicabilityTest:

  private val Refusal = "__guardrail-blocked__:"
  private val FailureMarker = "__turn-failed__:"
  private val Reference = "[1] (durability-tasks) The runtime persists the task."

  private def of(reply: Option[String], reference: String = Reference): Applicability =
    EvaluationApplicability.of(reply, reference, refusalPrefix = Refusal, failurePrefix = FailureMarker)

  @Test
  def anOrdinaryAnswerIsApplicableAndCarriesTheAnswer(): Unit =
    assertThat(of(Some("The runtime persists the task.")))
      .isEqualTo(Applicability.Applicable("The runtime persists the task."))

  /** The test that protects the second half of the capability. A decline is a *decision*, and judging
    * that decision is what the authored judge exists for — so it must reach the judges. */
  @Test
  def aDeclineIsApplicableBecauseTheDeclineItselfIsWhatIsJudged(): Unit =
    assertThat(of(Some("I don't know"))).isEqualTo(Applicability.Applicable("I don't know"))

  @Test
  def aRefusedInteractionIsNotApplicable(): Unit =
    assertThat(of(Some(Refusal + "Content similarity [0.77] exceeds threshold [0.75]")))
      .isEqualTo(Applicability.NotApplicable(EvaluationApplicability.RefusedReason))

  /** Timeout follow-up, path 1 — the turn failed *inside* the agent and replied behind the failure
    * marker. Before the marker existed this arrived as "I don't know" and was judged as a decline. */
  @Test
  def aTurnThatFailedInsideTheAgentIsNotApplicable(): Unit =
    assertThat(of(Some(FailureMarker + "Model request timeout")))
      .isEqualTo(Applicability.NotApplicable(EvaluationApplicability.FailedReason))

  /** Timeout follow-up, path 2 — the call itself failed, so there is no reply at all. */
  @Test
  def aCallThatFailedOutrightIsNotApplicable(): Unit =
    assertThat(of(None)).isEqualTo(Applicability.NotApplicable(EvaluationApplicability.FailedReason))

  /** T022 — covered here because this is where it is reachable. With capability 8's canned corpus
    * retrieval always returns top-3, so empty reference material cannot occur through
    * `POST /evaluate`; the rule still has to be right, and this is the honest place to prove it
    * rather than staging a fake path to it through the endpoint. */
  @Test
  def emptyReferenceMaterialIsNotApplicable(): Unit =
    assertThat(of(Some("some answer"), reference = ""))
      .isEqualTo(Applicability.NotApplicable(EvaluationApplicability.NoReferenceReason))
    assertThat(of(Some("some answer"), reference = "   "))
      .isEqualTo(Applicability.NotApplicable(EvaluationApplicability.NoReferenceReason))

  /** Order matters: "no answer was produced" is more specific, and more useful, than "there was
    * nothing to judge it against" — so both a refusal and a failure are reported ahead of it. */
  @Test
  def noAnswerIsReportedAheadOfMissingReferenceMaterial(): Unit =
    assertThat(of(Some(Refusal + "blocked"), reference = ""))
      .isEqualTo(Applicability.NotApplicable(EvaluationApplicability.RefusedReason))
    assertThat(of(Some(FailureMarker + "timeout"), reference = ""))
      .isEqualTo(Applicability.NotApplicable(EvaluationApplicability.FailedReason))
    assertThat(of(None, reference = ""))
      .isEqualTo(Applicability.NotApplicable(EvaluationApplicability.FailedReason))

  @Test
  def theThreeNotApplicableReasonsAreDistinct(): Unit =
    val reasons = Set(
      EvaluationApplicability.RefusedReason,
      EvaluationApplicability.FailedReason,
      EvaluationApplicability.NoReferenceReason
    )
    assertThat(reasons.size).isEqualTo(3)
