package com.gwgs.akkaagentic.eval.application

import java.util.UUID

import scala.util.Try

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.docs.application.DocsAgent
import com.gwgs.akkaagentic.eval.domain.ReferenceText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** T018 — the authored judge, driven the same way the SDK's own is: by component id through
  * `dynamicCall`.
  *
  * **What an offline test of an LLM judge can and cannot prove**, stated plainly because the
  * distinction is easy to fudge. It cannot prove that a real model judges *well* — that is not a
  * deterministic property and no test here asserts it. What it can prove, and does, is that the judge
  * receives the right three inputs, that the platform's parsing and mapping run, and that a verdict
  * flows back attributed and intact. The scripted responses below are made **conditional on the
  * judge's actual input**, so a test would fail if the wrong question, reference text or answer were
  * sent — which is the part that would otherwise be circular.
  */
class DeclineJudgeIntegrationTest extends TestKitSupport:

  private val judgeModel = new TestModelProvider()

  private val UncoveredQuestion = "what is the capital of France?"
  private val CoveredQuestion = "what makes agent work survive a restart?"
  private val Reference = ReferenceText.render(
    List(
      "durability-tasks" -> "The runtime persists the task and the agent's process state as the loop runs.",
      "cap-3-help-desk" -> "A task is a durable, queryable record of the agent's work."
    )
  )

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[DeclineJudge], judgeModel)

  @BeforeEach
  def reset(): Unit = judgeModel.reset()

  private def ask(question: String, referenceText: String, answer: String): DeclineJudge.Result =
    componentClient
      .forAgent()
      .inSession(UUID.randomUUID().toString)
      .dynamicCall[DeclineJudge.EvaluationRequest, DeclineJudge.Result]("decline-judge")
      .invoke(DeclineJudge.EvaluationRequest(question, referenceText, answer))

  /** US2 acceptance 1: the reference text does not cover the question, and the assistant declined —
    * the right call. The script fires only if the judge was actually shown the decline. */
  @Test
  def aDeclineAgainstUncoveredReferenceTextIsAppropriate(): Unit =
    judgeModel
      .whenUserMessage(m => m.content().contains(UncoveredQuestion) && m.content().contains(DocsAgent.DontKnow))
      .reply(
        """{"explanation":"The reference text is about durability and says nothing about France.","label":"appropriate"}"""
      )

    val result = ask(UncoveredQuestion, Reference, DocsAgent.DontKnow)

    assertThat(result.passed).isTrue()
    assertThat(result.explanation).contains("nothing about France")

  /** US2 acceptance 2 — the failure capability 8 could never see: declining when the passages *did*
    * answer the question. Uselessly cautious, and previously invisible. */
  @Test
  def aDeclineAgainstCoveringReferenceTextIsInappropriate(): Unit =
    judgeModel
      .whenUserMessage(m => m.content().contains(CoveredQuestion) && m.content().contains(DocsAgent.DontKnow))
      .reply(
        """{"explanation":"Passage [1] states the runtime persists the task, which answers the question.","label":"inappropriate"}"""
      )

    val result = ask(CoveredQuestion, Reference, DocsAgent.DontKnow)

    assertThat(result.passed).isFalse()
    assertThat(result.explanation).contains("Passage [1]")

  /** US2 acceptance 3 — the opposite error, and the more dangerous one: answering when the passages
    * do not support an answer. A judge told only "was the decline right?" would miss this entirely,
    * which is why the system message is two-sided. */
  @Test
  def anAnswerUnsupportedByTheReferenceTextIsInappropriate(): Unit =
    val unsupported = "The capital of France is Paris."
    judgeModel
      .whenUserMessage(m => m.content().contains(unsupported))
      .reply(
        """{"explanation":"The reference text does not mention France; the assistant should have declined.","label":"inappropriate"}"""
      )

    val result = ask(UncoveredQuestion, Reference, unsupported)

    assertThat(result.passed).isFalse()
    assertThat(result.explanation).contains("should have declined")

  /** The judge is shown all three inputs — question, reference text, answer. This is the capability's
    * reason to exist: capability 12's `TextGuardrail.evaluate` receives the answer text alone, so a
    * grounding or decline judgement was structurally out of reach there. */
  @Test
  def theJudgeReceivesQuestionReferenceTextAndAnswer(): Unit =
    var seen: String = null
    judgeModel
      .whenUserMessage { m => seen = m.content(); true }
      .reply("""{"explanation":"ok","label":"appropriate"}""")

    ask(CoveredQuestion, Reference, DocsAgent.DontKnow)

    assertThat(seen).contains(CoveredQuestion)
    assertThat(seen).contains(Reference)
    assertThat(seen).contains(DocsAgent.DontKnow)

  /** An unrecognised label must surface as a failure, not as an unfavourable verdict. Note the type is
    * erased at the component-client boundary (research T003), so the *message* is what carries the
    * reason — which is exactly what `AnswerEvaluator` puts into an `errored` verdict. */
  @Test
  def anUnrecognisedLabelFailsTheCallRatherThanReturningAVerdict(): Unit =
    judgeModel.fixedResponse("""{"explanation":"I am not sure","label":"maybe"}""")

    val thrown = Try(ask(CoveredQuestion, Reference, DocsAgent.DontKnow)).failed.get

    assertThat(thrown.getMessage).contains("Unknown evaluation label [maybe]")

  /** T019(d) — to a consumer of the registry, the judge we wrote and the judge the SDK ships are the
    * same kind of thing (SC-007). `@AgentRole("evaluator")` is what puts ours in that set. */
  @Test
  def theAuthoredJudgeAndTheSdksJudgeAreBothRegisteredAsEvaluators(): Unit =
    val evaluators = testKit.getAgentRegistry.agentsWithRole("evaluator")
    val ids = evaluators.stream().map(_.id()).toArray.map(_.toString).toList.sorted.mkString(", ")

    // Asserted as one string: a Scala List is not a java.util.List, so AssertJ falls back to
    // ObjectAssert and loses `contains`.
    assertThat(ids).contains("decline-judge")
    assertThat(ids).contains("hallucination-evaluator")
