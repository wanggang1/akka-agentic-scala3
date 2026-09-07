package com.gwgs.akkaagentic.eval.domain

import com.gwgs.akkaagentic.eval.domain.EvaluationApplicability.Applicability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T006/T022 — the applicability rules, unit-tested with no runtime and no model.
  *
  * The prefix is passed in rather than imported, so these tests use a stand-in constant: the domain
  * must work for *any* refusal marker, not just capability 8's.
  */
class EvaluationApplicabilityTest:

  private val Refusal = "__guardrail-blocked__:"
  private val Reference = "[1] (durability-tasks) The runtime persists the task."

  @Test
  def anOrdinaryAnswerIsApplicable(): Unit =
    assertThat(
      EvaluationApplicability.of("The runtime persists the task.", Reference, Refusal)
    ).isEqualTo(Applicability.Applicable)

  /** The test that protects the second half of the capability. A decline is a *decision*, and judging
    * that decision is what the authored judge exists for — so it must reach the judges. */
  @Test
  def aDeclineIsApplicableBecauseTheDeclineItselfIsWhatIsJudged(): Unit =
    assertThat(
      EvaluationApplicability.of("I don't know", Reference, Refusal)
    ).isEqualTo(Applicability.Applicable)

  @Test
  def aRefusedInteractionIsNotApplicable(): Unit =
    assertThat(
      EvaluationApplicability.of(
        Refusal + "Content similarity [0.77] exceeds threshold [0.75]",
        Reference,
        Refusal
      )
    ).isEqualTo(Applicability.NotApplicable(EvaluationApplicability.RefusedReason))

  /** T022 — covered here because this is where it is reachable. With capability 8's canned corpus
    * retrieval always returns top-3, so empty reference material cannot occur through
    * `POST /evaluate`; the rule still has to be right, and this is the honest place to prove it
    * rather than staging a fake path to it through the endpoint. */
  @Test
  def emptyReferenceMaterialIsNotApplicable(): Unit =
    assertThat(
      EvaluationApplicability.of("some answer", "", Refusal)
    ).isEqualTo(Applicability.NotApplicable(EvaluationApplicability.NoReferenceReason))
    assertThat(
      EvaluationApplicability.of("some answer", "   ", Refusal)
    ).isEqualTo(Applicability.NotApplicable(EvaluationApplicability.NoReferenceReason))

  /** Order matters: a refusal against empty reference material reports the refusal, which is the
    * more specific and more useful explanation. */
  @Test
  def refusalIsReportedAheadOfMissingReferenceMaterial(): Unit =
    assertThat(
      EvaluationApplicability.of(Refusal + "blocked", "", Refusal)
    ).isEqualTo(Applicability.NotApplicable(EvaluationApplicability.RefusedReason))

  @Test
  def theTwoNotApplicableReasonsAreDistinct(): Unit =
    assertThat(EvaluationApplicability.RefusedReason)
      .isNotEqualTo(EvaluationApplicability.NoReferenceReason)
