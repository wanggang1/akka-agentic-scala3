package com.gwgs.akkaagentic.docs.api

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.docs.application.DocsAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Shared fixture for the SC-005 pair below.
  *
  * The two subclasses run **the same guardrail class over the same production declaration** and
  * differ in exactly one thing: whether `report-only` is overridden. Everything else — the rule, its
  * category, the agent it is attached to, the scripted answer, the assertions' subject — is identical.
  * That is what makes the pair a demonstration of SC-005 rather than two unrelated tests.
  */
abstract class AnswerLengthGuardFixture extends TestKitSupport:

  protected val docsModel = new TestModelProvider()

  /** Five sentences — comfortably over `AnswerLengthGuard`'s limit of two, and containing no link, so
    * the *only* rule it can trip is the length one. */
  protected val OverLongAnswer: String =
    "Components are Java when their client needs a method reference. That client is keyed on a " +
      "SerializedLambda. A Scala lambda compiles to a synthetic anonfun. So resolution fails. " +
      "The escape hatch is dynamicCall."

  protected val InCorpusQuestion = "why can some components only be written in Java, not Scala?"

  @BeforeEach
  def reset(): Unit = docsModel.reset()

  protected def ask(question: String) =
    httpClient
      .POST("/ask")
      .withRequestBody(DocsEndpoint.AskRequest(Some(question)))
      .invoke() // no responseBodyAs: it throws on a non-2xx status

/** US3/SC-004 — a record-only rule is **caller-invisible**.
  *
  * This class adds no configuration at all: it runs `application.conf` exactly as shipped, where
  * `"answer length guard"` is declared `report-only = true`.
  *
  * Note what this test can and cannot assert. A `report-only` rule does not fail the interaction, so
  * it never reaches `DocsAgent.onFailure` and **application code sees nothing whatsoever** — no
  * exception, no altered reply. Its violations are recorded solely by the runtime's instrumentation,
  * which test code cannot reach (T011). So the assertion here is the *absence* of a caller-visible
  * change, which is precisely what SC-004 claims; the recording itself is verified in traces, not
  * here.
  */
class GuardrailReportOnlyIntegrationTest extends AnswerLengthGuardFixture:

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[DocsAgent], docsModel)

  @Test
  def anOverLongAnswerIsStillDelivered(): Unit =
    docsModel.fixedResponse(OverLongAnswer)

    val reply = httpClient
      .POST("/ask")
      .withRequestBody(DocsEndpoint.AskRequest(Some(InCorpusQuestion)))
      .responseBodyAs(classOf[DocsEndpoint.AskReply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(OverLongAnswer) // delivered verbatim, not truncated
    assertThat(reply.body().citedSources.contains("interop-method-ref-wall")).isTrue()

  /** A short answer is unremarkable either way — the control case, so the test above is known to be
    * exercising the rule's *violating* branch rather than a rule that never fires. */
  @Test
  def aShortAnswerIsDeliveredToo(): Unit =
    val short = "Because the client is keyed on a Java method reference."
    docsModel.fixedResponse(short)

    val reply = httpClient
      .POST("/ask")
      .withRequestBody(DocsEndpoint.AskRequest(Some(InCorpusQuestion)))
      .responseBodyAs(classOf[DocsEndpoint.AskReply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(short)

/** US3/SC-005 — the same rule, flipped to enforcing by **one configuration key**.
  *
  * The only difference from [[GuardrailReportOnlyIntegrationTest]] is the single
  * `report-only = false` override below. No source file changes between the two runs; the same
  * `AnswerLengthGuard` class, the same declaration, the same scripted answer. The caller-visible
  * outcome changes from `200` to `422`.
  *
  * That is the zero-code-change claim demonstrated by the suite rather than asserted in prose — and
  * it is why the record-only mode cost one config string instead of a second implementation.
  */
class GuardrailEnforcingOverrideIntegrationTest extends AnswerLengthGuardFixture:

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("""
        |akka.javasdk.agent.googleai-gemini.api-key = "n/a"
        |akka.javasdk.agent.guardrails."answer length guard".report-only = false
        |""".stripMargin)
      .withModelProvider(classOf[DocsAgent], docsModel)

  @Test
  def theSameOverLongAnswerIsNowBlocked(): Unit =
    docsModel.fixedResponse(OverLongAnswer)

    val response = ask(InCorpusQuestion)
    assertThat(response.status()).isEqualTo(StatusCodes.UNPROCESSABLE_ENTITY)

    val body = response.body().utf8String
    assertThat(body).contains("\"blocked\":true")
    // The no-arg form cannot read its declared name, so these come from constants in the class —
    // which is exactly the cost documented in AnswerLengthGuard's scaladoc.
    assertThat(body).contains("\"rule\":\"answer length guard\"")
    assertThat(body).contains("\"category\":\"FORMAT\"")
    assertThat(body).contains("over the limit of 2")

  /** Enforcing mode must not become indiscriminate: a compliant answer still passes. */
  @Test
  def aShortAnswerStillPassesUnderEnforcement(): Unit =
    val short = "Because the client is keyed on a Java method reference."
    docsModel.fixedResponse(short)

    val reply = httpClient
      .POST("/ask")
      .withRequestBody(DocsEndpoint.AskRequest(Some(InCorpusQuestion)))
      .responseBodyAs(classOf[DocsEndpoint.AskReply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(short)
