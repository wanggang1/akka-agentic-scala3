package com.gwgs.akkaagentic.eval.api

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.agent.evaluator.HallucinationEvaluator
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.docs.application.DocsAgent
import com.gwgs.akkaagentic.eval.application.DeclineJudge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** T025 / SC-005 — evaluation switches off with **one configuration key and zero lines of code**.
  *
  * This class differs from [[EvaluationEndpointIntegrationTest]] in exactly one place: the added
  * `eval.enabled = false`. Everything else — the same endpoint, the same evaluator, the same judges —
  * is untouched. That is the proof; the same technique capability 12 used for its `report-only` pair,
  * where two classes over the same guardrail differed only in one key.
  *
  * Deliberately, **no model is scripted for either judge**. If the switch failed to take effect a
  * judge would be called, `TestModelProvider` would fail it, and the verdict would come back
  * `errored` — a visibly wrong result rather than a silently passing test.
  */
class EvaluationDisabledIntegrationTest extends TestKitSupport:

  private val docsModel = new TestModelProvider()
  private val judgeModel = new TestModelProvider()
  private val declineJudgeModel = new TestModelProvider()

  private val DurabilityQuestion =
    "what makes agent work survive a restart without writing persistence code?"

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("""
        |akka.javasdk.agent.googleai-gemini.api-key = n/a
        |eval.enabled = false
        |""".stripMargin)
      .withModelProvider(classOf[DocsAgent], docsModel)
      .withModelProvider(classOf[HallucinationEvaluator], judgeModel)
      .withModelProvider(classOf[DeclineJudge], declineJudgeModel)

  @BeforeEach
  def reset(): Unit =
    docsModel.reset()
    judgeModel.reset()
    declineJudgeModel.reset()

  /** FR-009: the answer path is never the thing that breaks. With judges off the question is still
    * answered, still cited, still `200` — only the verdicts are absent. */
  @Test
  def withJudgesDisabledTheAnswerIsUnchangedAndNoVerdictsAreReturned(): Unit =
    val answer =
      "The runtime persists the task and the agent's process state, so work survives a restart."
    docsModel.fixedResponse(answer)

    val reply = httpClient
      .POST("/evaluate")
      .withRequestBody(EvaluationEndpoint.EvaluateRequest(Some(DurabilityQuestion)))
      .responseBodyAs(classOf[EvaluationEndpoint.EvaluateReply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    val body = reply.body()

    assertThat(body.answer).isEqualTo(answer)
    assertThat(body.citedSources.contains("durability-tasks")).isTrue()
    assertThat(body.evaluationId).isNotEmpty()

    // Empty, not "errored" — the judges were never reached. Had the switch not worked, the unscripted
    // judge models would have produced two `errored` verdicts here.
    assertThat(body.verdicts.isEmpty).isTrue()

  /** Validation still runs first when evaluation is off — the switch turns judges off, not the
    * endpoint's contract (FR-010). */
  @Test
  def validationStillRunsFirstWhenDisabled(): Unit =
    val reply = httpClient
      .POST("/evaluate")
      .withRequestBody(EvaluationEndpoint.EvaluateRequest(Some("   ")))
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** A decline is still a decline with judges off — capability 8's behaviour is reached through this
    * surface unchanged in either switch position. */
  @Test
  def aDeclineIsStillReportedWhenDisabled(): Unit =
    docsModel.fixedResponse(DocsAgent.DontKnow)

    val body = httpClient
      .POST("/evaluate")
      .withRequestBody(EvaluationEndpoint.EvaluateRequest(Some("what is the capital of France?")))
      .responseBodyAs(classOf[EvaluationEndpoint.EvaluateReply])
      .invoke()
      .body()

    assertThat(body.answer).isEqualTo(DocsAgent.DontKnow)
    assertThat(body.citedSources.isEmpty).isTrue()
    assertThat(body.verdicts.isEmpty).isTrue()
