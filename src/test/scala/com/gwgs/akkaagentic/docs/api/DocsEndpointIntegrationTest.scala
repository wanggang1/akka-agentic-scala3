package com.gwgs.akkaagentic.docs.api

import akka.http.javadsl.model.{ContentTypes, StatusCodes}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.docs.application.DocsAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[DocsEndpoint]] over HTTP with `DocsAgent`'s model mocked (no live model).
  *
  * The grounded/decline *answer* is mocked here; the *retrieval* it grounds on is the real in-process
  * embeddings (deterministic), so citations are asserted against the passages retrieval actually
  * returns. Retrieval correctness itself is proven separately and offline in
  * [[com.gwgs.akkaagentic.docs.application.KnowledgeStoreTest]] (SC-004).
  */
class DocsEndpointIntegrationTest extends TestKitSupport:

  private val docsModel = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[DocsAgent], docsModel)

  @BeforeEach
  def reset(): Unit = docsModel.reset()

  /** US1/SC-001: an in-corpus question returns the grounded answer and cites the right source.
    * The question paraphrases the durability passage (no shared keywords like "persist"), so the
    * citation proves *semantic* retrieval fed the grounding, not keyword overlap. */
  @Test
  def inCorpusQuestionReturnsGroundedAnswerWithCitation(): Unit =
    val grounded = "The runtime persists the task and the agent's state as the loop runs, so the work survives a restart."
    docsModel.fixedResponse(grounded)

    val reply = httpClient
      .POST("/ask")
      .withRequestBody(DocsEndpoint.AskRequest(Some("what makes agent work survive a restart without writing persistence code?")))
      .responseBodyAs(classOf[DocsEndpoint.AskReply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(grounded)
    assertThat(reply.body().citedSources.contains("durability-tasks")).isTrue() // top retrieved source
    assertThat(reply.body().citedSources.isEmpty).isFalse()

  /** US2/SC-003: when the model declines with the sentinel, the reply carries NO citations — the
    * decline path must not fabricate a source even though passages were retrieved. */
  @Test
  def outOfCorpusQuestionDeclinesWithNoCitation(): Unit =
    docsModel.fixedResponse(DocsAgent.DontKnow)

    val reply = httpClient
      .POST("/ask")
      .withRequestBody(DocsEndpoint.AskRequest(Some("what is the capital of France?")))
      .responseBodyAs(classOf[DocsEndpoint.AskReply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(DocsAgent.DontKnow)
    assertThat(reply.body().citedSources.isEmpty).isTrue() // decline cites nothing (FR-005)

  /** US3/SC-005: a blank question is rejected up front — `400`, no model call. (No `responseBodyAs`,
    * per the httpClient failure-status pattern: it would throw on a non-2xx status.) */
  @Test
  def blankQuestionRejected(): Unit =
    val reply = httpClient
      .POST("/ask")
      .withRequestBody(DocsEndpoint.AskRequest(Some("   ")))
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US3: an absent `question` field deserializes to `None` → `400`, not a `500`. */
  @Test
  def absentQuestionRejected(): Unit =
    val reply = httpClient
      .POST("/ask")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US3: a malformed JSON body is rejected by the SDK boundary → `400`. */
  @Test
  def malformedBodyRejected(): Unit =
    val reply = httpClient
      .POST("/ask")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{ \"question\": ".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US3/FR-007: an unknown extra property alongside a valid question is tolerated → `200`. */
  @Test
  def unknownPropertyTolerated(): Unit =
    docsModel.fixedResponse("A grounded answer.")
    val reply = httpClient
      .POST("/ask")
      .withRequestBody(ContentTypes.APPLICATION_JSON, """{"question":"how does session memory work?","surprise":"ignored"}""".getBytes)
      .responseBodyAs(classOf[DocsEndpoint.AskReply])
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo("A grounded answer.")

  /** US3/FR-008: two distinct questions each return their own answer — no cross-request state. */
  @Test
  def requestsAreIndependent(): Unit =
    docsModel.fixedResponse("Answer for both, but each request is served on its own fresh session.")
    def ask(q: String) =
      httpClient
        .POST("/ask")
        .withRequestBody(DocsEndpoint.AskRequest(Some(q)))
        .responseBodyAs(classOf[DocsEndpoint.AskReply])
        .invoke()

    val a = ask("how does the coordinator pick which specialist to consult?")
    val b = ask("why can some components only be written in Java?")
    assertThat(a.status()).isEqualTo(StatusCodes.OK)
    assertThat(b.status()).isEqualTo(StatusCodes.OK)
    // Citations come from each request's own retrieval, not shared state.
    assertThat(a.body().citedSources.contains("cap-7-activity-coordinator")).isTrue()
    assertThat(b.body().citedSources.contains("interop-method-ref-wall")).isTrue()
