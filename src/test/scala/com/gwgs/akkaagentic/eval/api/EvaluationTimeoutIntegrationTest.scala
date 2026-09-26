package com.gwgs.akkaagentic.eval.api

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.agent.evaluator.HallucinationEvaluator
import akka.javasdk.testkit.TestModelProvider.{AiResponse, InputMessage}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.docs.application.DocsAgent
import com.gwgs.akkaagentic.eval.application.DeclineJudge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Tag, Test}

/** Gate 2 of the judge-timeout follow-up: the two judges run **concurrently**, and each is bounded by
  * `eval.judge-timeout`.
  *
  * Differs from the other evaluation tests in one key — a 3s deadline — so a slow judge is cheap to
  * stage: the scripted model sleeps before replying, which is exactly what a slow model looks like
  * from `AnswerEvaluator`'s side. Nothing is broken to produce it.
  *
  * The timing bounds are wide on purpose. A missed deadline must return well before the judge's sleep
  * ends; concurrent judges must finish well under the sum of both sleeps. They measure the mechanism,
  * not the machine.
  */
@Tag("slow")
class EvaluationTimeoutIntegrationTest extends TestKitSupport:

  private val docsModel = new TestModelProvider()
  private val judgeModel = new TestModelProvider()
  private val declineJudgeModel = new TestModelProvider()

  private val DurabilityQuestion =
    "what makes agent work survive a restart without writing persistence code?"
  private val Answer =
    "The runtime persists the task and the agent's process state, so work survives a restart."
  private val Grounded = """{"explanation":"Supported by the reference text.","label":"factual"}"""
  private val Appropriate =
    """{"explanation":"The reference text supports an answer and the assistant answered.","label":"appropriate"}"""

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig(
        """akka.javasdk.agent.googleai-gemini.api-key = n/a
          |eval.judge-timeout = 3s""".stripMargin
      )
      .withModelProvider(classOf[DocsAgent], docsModel)
      .withModelProvider(classOf[HallucinationEvaluator], judgeModel)
      .withModelProvider(classOf[DeclineJudge], declineJudgeModel)

  @BeforeEach
  def reset(): Unit =
    docsModel.reset()
    judgeModel.reset()
    declineJudgeModel.reset()
    docsModel.fixedResponse(Answer)

  /** A scripted model that replies only after `millis` — a slow model, as seen from here. */
  private def slowly(millis: Long, reply: String): java.util.function.Function[InputMessage, AiResponse] =
    _ =>
      Thread.sleep(millis)
      new AiResponse(reply)

  private def timed[T](body: => T): (T, Long) =
    val start = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - start) / 1_000_000)

  private def evaluate() =
    httpClient
      .POST("/evaluate")
      .withRequestBody(EvaluationEndpoint.EvaluateRequest(Some(DurabilityQuestion)))
      .responseBodyAs(classOf[EvaluationEndpoint.EvaluateReply])
      .invoke()

  private def outcomes(reply: EvaluationEndpoint.EvaluateReply): String =
    reply.verdicts.map(v => s"${v.judge}=${v.outcome}").mkString(", ")

  /** The case the follow-up was opened for: a judge that does not answer. It is `errored` with a
    * reason that says so, the other judge still reports, the answer is untouched — and the request is
    * bounded by the deadline rather than by the judge. */
  @Test
  def aJudgeThatMissesTheDeadlineIsErroredAndTheOtherStillReports(): Unit =
    judgeModel.fixedResponse(slowly(8000, Grounded))
    declineJudgeModel.fixedResponse(Appropriate)

    val (reply, elapsedMs) = timed(evaluate())

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    val body = reply.body()
    assertThat(outcomes(body)).isEqualTo("hallucination-evaluator=errored, decline-judge=passed")
    assertThat(body.verdicts.head.explanation).isEqualTo("the judge did not respond within 3s")
    assertThat(body.answer).isEqualTo(Answer)

    // Returned on the 3s deadline, long before the judge's 8s sleep would have ended.
    assertThat(elapsedMs).isLessThan(6000L)

  /** Run one after the other, two 2.5s judges take at least 5s; run concurrently, one sleep plus
    * overhead. Verdict order stays fixed regardless of which finishes first. */
  @Test
  def bothJudgesRunConcurrently(): Unit =
    judgeModel.fixedResponse(slowly(2500, Grounded))
    declineJudgeModel.fixedResponse(slowly(2500, Appropriate))

    val (reply, elapsedMs) = timed(evaluate())

    assertThat(outcomes(reply.body())).isEqualTo("hallucination-evaluator=passed, decline-judge=passed")
    assertThat(elapsedMs).isLessThan(4200L)
