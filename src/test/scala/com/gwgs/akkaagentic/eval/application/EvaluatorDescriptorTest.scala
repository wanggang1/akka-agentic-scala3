package com.gwgs.akkaagentic.eval.application

import scala.io.Source
import scala.util.Using

import akka.javasdk.agent.EvaluationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T019 — pins research R2's registration facts, so a future SDK change breaks the suite rather than
  * the service. No runtime needed: these are properties of a class and of a text file.
  */
class EvaluatorDescriptorTest:

  private val DescriptorResource =
    "META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf"

  private lazy val descriptor: String =
    Using.resource(Source.fromResource(DescriptorResource))(_.mkString)

  /** (a) The quiet failure mode. `Reflect$.isEvaluatorAgent` asks exactly this question, and the
    * answer is what flags the agent as an evaluator in its descriptor and routes its verdicts into
    * metrics and traces (FR-011). Drop `extends EvaluationResult` and the agent still compiles, still
    * answers, and is silently un-instrumented — which no other test would notice. */
  @Test
  def theAuthoredJudgesResultImplementsTheSdksEvaluationResult(): Unit =
    assertThat(classOf[EvaluationResult].isAssignableFrom(classOf[DeclineJudge.Result])).isTrue()

  /** Not a tautology: it asserts the interface is satisfied by the *case class accessors*, so a
    * refactor to a plain class with differently-named getters is caught. */
  @Test
  def theResultAnswersTheEvaluationResultInterface(): Unit =
    val verdict: EvaluationResult = DeclineJudge.Result("because the passages cover it", passed = false)
    assertThat(verdict.passed).isFalse()
    assertThat(verdict.explanation).isEqualTo("because the passages cover it")

  /** (b) An evaluator is an ordinary agent, so — unlike capability 12's guardrails — it needs a
    * descriptor line. Governance cost zero lines; evaluation costs one. */
  @Test
  def theAuthoredJudgeIsListedAsAnAgent(): Unit =
    assertThat(descriptor).contains("com.gwgs.akkaagentic.eval.application.DeclineJudge")

    val agentBlock = descriptor.split("""autonomous-agent""").head
    assertThat(agentBlock).contains("com.gwgs.akkaagentic.eval.application.DeclineJudge")

  /** (c) The SDK's three judges must NOT be listed. They are provided components, registered by
    * `ComponentLocator$` alongside `SessionMemoryEntity` and `TaskEntity` (research R1c). Listing one
    * would be a duplicate registration; this test states the reason it is absent, so nobody "fixes"
    * the omission later. */
  @Test
  def theSdksBuiltInJudgesAreNotListedBecauseTheRuntimeProvidesThem(): Unit =
    val leaked = List(
      "akka.javasdk.agent.evaluator.HallucinationEvaluator",
      "akka.javasdk.agent.evaluator.ToxicityEvaluator",
      "akka.javasdk.agent.evaluator.SummarizationEvaluator"
    ).filter(descriptor.contains)

    assertThat(leaked.mkString(", ")).isEqualTo("")

  /** Capability 13 adds exactly two descriptor lines and edits none — the additive-only half of the
    * SC-003 claim (the byte-identity half is `git diff`, asserted at T031). */
  @Test
  def capability13AddsExactlyTwoDescriptorEntries(): Unit =
    val added = descriptor.linesIterator.count(l => l.contains("com.gwgs.akkaagentic.eval."))
    assertThat(added).isEqualTo(2)
    assertThat(descriptor).contains("com.gwgs.akkaagentic.eval.api.EvaluationEndpoint")
