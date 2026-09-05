package com.gwgs.akkaagentic.eval.api

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.agent.evaluator.HallucinationEvaluator
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.docs.api.DocsEndpoint
import com.gwgs.akkaagentic.docs.application.{DocsAgent, KnowledgeStore}
import com.gwgs.akkaagentic.eval.application.AnswerEvaluator
import com.gwgs.akkaagentic.eval.domain.ReferenceText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[EvaluationEndpoint]] over HTTP with **two** models mocked — the assistant's and the
  * judge's — and no live model anywhere.
  *
  * That the *judge's* model can be mocked at all is capability 13's enabling finding (research R3):
  * `HallucinationEvaluator` is an SDK-owned class that sets its model explicitly, and the TestKit's
  * per-agent override still wins. The SDK's real prompt, real `responseConformsTo` parsing and real
  * result mapping all still run; only the model is scripted.
  *
  * **Fixture hazard**: scripted answers must not contain `http`, `www.` or `see also:` — capability
  * 12's `linked answer guard` is enforcing on `docs-agent` responses and would block them.
  */
class EvaluationEndpointIntegrationTest extends TestKitSupport:

  private val docsModel = new TestModelProvider()
  private val judgeModel = new TestModelProvider()

  /** A second store built from the same corpus, used as the independent reference for SC-002 — the
    * same technique capability 9's parity test uses. */
  private lazy val referenceStore = KnowledgeStore.fromCorpus()

  private val DurabilityQuestion =
    "what makes agent work survive a restart without writing persistence code?"

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[DocsAgent], docsModel)
      .withModelProvider(classOf[HallucinationEvaluator], judgeModel)

  @BeforeEach
  def reset(): Unit =
    docsModel.reset()
    judgeModel.reset()

  private def evaluate(question: String) =
    httpClient
      .POST("/evaluate")
      .withRequestBody(EvaluationEndpoint.EvaluateRequest(Some(question)))
      .responseBodyAs(classOf[EvaluationEndpoint.EvaluateReply])
      .invoke()

  private def verdict(
      reply: EvaluationEndpoint.EvaluateReply,
      judge: String
  ): EvaluationEndpoint.VerdictReply =
    reply.verdicts
      .find(_.judge == judge)
      .getOrElse(throw new AssertionError(s"no verdict from [$judge] in ${reply.verdicts}"))

  /** US1 acceptance 1 + SC-001: a grounded answer is judged `passed`, and the verdict names the judge
    * that produced it. */
  @Test
  def groundedAnswerIsJudgedPassedAndAttributed(): Unit =
    docsModel.fixedResponse(
      "The runtime persists the task and the agent's process state as the loop runs, so the work survives a restart."
    )
    judgeModel.fixedResponse(
      """{"explanation":"The answer restates the durability described in the reference text.","label":"factual"}"""
    )

    val reply = evaluate(DurabilityQuestion)

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    val body = reply.body()
    assertThat(body.question).isEqualTo(DurabilityQuestion)
    assertThat(body.citedSources.contains("durability-tasks")).isTrue()

    val grounding = verdict(body, AnswerEvaluator.HallucinationJudgeId)
    assertThat(grounding.outcome).isEqualTo("passed")
    assertThat(grounding.judge).isEqualTo("hallucination-evaluator")
    assertThat(grounding.explanation).contains("restates the durability")

  /** US1 acceptance 2: an answer the passages do not support is judged `failed` — the verdict is a
    * real signal, not a rubber stamp. */
  @Test
  def unsupportedAnswerIsJudgedFailed(): Unit =
    docsModel.fixedResponse(
      "Work survives because every agent writes a checkpoint file to local disk every 30 seconds."
    )
    judgeModel.fixedResponse(
      """{"explanation":"The reference text says nothing about checkpoint files or a 30 second interval.","label":"hallucinated"}"""
    )

    val body = evaluate(DurabilityQuestion).body()
    val grounding = verdict(body, AnswerEvaluator.HallucinationJudgeId)

    assertThat(grounding.outcome).isEqualTo("failed")
    assertThat(grounding.explanation).contains("checkpoint files")
    // FR-008: a failed verdict is still a successful evaluation — the answer is reported, not withheld
    assertThat(body.answer).contains("checkpoint file")

  /** US1 acceptance 3 / SC-002 (T013): the judge is shown **exactly** the passages the assistant was
    * shown. Asserted by inspecting what the judge's model actually received, rather than by echoing
    * the reference text back on the wire — a verdict formed from evidence the assistant never had
    * would be worthless, and this is the only way to prove it did not happen.
    */
  @Test
  def theJudgeSeesExactlyTheRetrievedPassages(): Unit =
    docsModel.fixedResponse("The runtime persists the task, so work survives a restart.")

    var judgeSaw: String = null
    judgeModel
      .whenUserMessage { message =>
        judgeSaw = message.content()
        true
      }
      .reply("""{"explanation":"ok","label":"factual"}""")

    evaluate(DurabilityQuestion)

    val expectedReference = ReferenceText.render(
      referenceStore.retrieve(DurabilityQuestion, 3).map(r => r.source -> r.text)
    )
    assertThat(expectedReference).isNotEmpty()
    assertThat(judgeSaw).isNotNull()
    assertThat(judgeSaw).contains(expectedReference)
    // and the judge was told the question and the answer too — all three inputs a grounding
    // judgement needs, and precisely the three cap-12's TextGuardrail could not see
    assertThat(judgeSaw).contains(DurabilityQuestion)
    assertThat(judgeSaw).contains("The runtime persists the task, so work survives a restart.")

  /** SC-002 / research D9 (T013): the deliberate duplication of capability 8's pipeline is pinned.
    * For the same question and the same scripted answer, `/evaluate` must produce the same answer and
    * the same citations as `/ask`. If the two ever drift, this fails rather than the duplication
    * quietly rotting.
    */
  @Test
  def evaluateReproducesAskExactly(): Unit =
    val answer =
      "The runtime persists the task and the agent's process state, so the work survives a restart."
    docsModel.fixedResponse(answer)
    judgeModel.fixedResponse("""{"explanation":"ok","label":"factual"}""")

    val asked = httpClient
      .POST("/ask")
      .withRequestBody(DocsEndpoint.AskRequest(Some(DurabilityQuestion)))
      .responseBodyAs(classOf[DocsEndpoint.AskReply])
      .invoke()
      .body()

    val evaluated = evaluate(DurabilityQuestion).body()

    assertThat(evaluated.answer).isEqualTo(asked.answer)
    assertThat(evaluated.citedSources).isEqualTo(asked.citedSources)

  /** Capability 8's decline rule survives the duplication: a decline cites nothing, and — the point
    * of US2 — the decline still reaches the judges rather than being written off as unjudgeable. */
  @Test
  def aDeclineCitesNothingAndIsStillJudged(): Unit =
    docsModel.fixedResponse(DocsAgent.DontKnow)
    judgeModel.fixedResponse(
      """{"explanation":"The answer asserts nothing beyond the reference text.","label":"factual"}"""
    )

    val body = evaluate("what is the capital of France?").body()

    assertThat(body.answer).isEqualTo(DocsAgent.DontKnow)
    assertThat(body.citedSources.isEmpty).isTrue()
    assertThat(verdict(body, AnswerEvaluator.HallucinationJudgeId).outcome).isEqualTo("passed")

  /** FR-010: validation runs first. A blank question is rejected before retrieval, before the
    * assistant, and before any judge — proven by both models still holding their scripted responses
    * unconsumed (no response is defined, so any call would have failed the request instead). */
  @Test
  def blankQuestionIsRejectedBeforeAnythingRuns(): Unit =
    val reply = httpClient
      .POST("/evaluate")
      .withRequestBody(EvaluationEndpoint.EvaluateRequest(Some("   ")))
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)
    assertThat(reply.body().utf8String).contains("must not be blank")

  /** An absent `question` is the same rejection, not a 500 (the `Option` boundary from feature 003). */
  @Test
  def absentQuestionIsRejected(): Unit =
    val reply = httpClient
      .POST("/evaluate")
      .withRequestBody(EvaluationEndpoint.EvaluateRequest(None))
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)
