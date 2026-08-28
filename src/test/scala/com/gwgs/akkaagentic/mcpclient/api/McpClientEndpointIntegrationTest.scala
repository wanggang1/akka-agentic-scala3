package com.gwgs.akkaagentic.mcpclient.api

import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.model.{ContentTypes, StatusCodes}
import akka.javasdk.JsonSupport
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.javasdk.testkit.TestModelProvider.{AiResponse, ToolInvocationRequest, ToolResult, UserMessage}
import com.gwgs.akkaagentic.docs.application.KnowledgeStore
import com.gwgs.akkaagentic.mcpclient.application.McpClientAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[McpClientEndpoint]] over HTTP (`POST /grounded-ask`) with [[McpClientAgent]]'s model
  * mocked — but the mock scripts a **real** `retrieve` MCP tool call, so the tool round-trips to this
  * service's own in-process cap-9 `/mcp` server (settled in the spikes, research R2/R3). No live model.
  *
  * The agentic-RAG loop is scripted as two model turns: turn 1 (user message) replies with a
  * `retrieve` tool call; the SDK invokes the real remote tool; turn 2 (`whenToolResult`) answers from
  * the returned `ToolResult`. Because the in-process embeddings are deterministic, the passages the
  * tool returns are ground truth (`KnowledgeStore.fromCorpus().retrieve`), which is what lets the
  * closed loop be asserted offline.
  */
class McpClientEndpointIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      // Align the running service name so the agent's fromService(...) resolves to this service's /mcp.
      .withServiceName(McpClientAgent.DefaultServiceName)
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[McpClientAgent], model)

  /** Ground truth for parity — the same deterministic store cap-9 injects behind the MCP tool. */
  private lazy val referenceStore = KnowledgeStore.fromCorpus()
  private val TopK = 3

  @BeforeEach
  def reset(): Unit = model.reset()

  private def post(question: String) =
    httpClient
      .POST("/grounded-ask")
      .withRequestBody(McpClientEndpoint.AskRequest(Some(question)))
      .responseBodyAs(classOf[McpClientEndpoint.AskReply])
      .invoke()

  /** US1 / SC-001 + SC-003: an in-corpus question yields a grounded `200` answer produced by the model
    * calling the remote `retrieve` tool. The captured tool result proves the loop actually closed
    * (agent → MCP client → our /mcp → KnowledgeStore) — its top source is the semantically-correct
    * passage, which could only come from the real retrieval, not the mock. */
  @Test
  def inCorpusQuestionAnswersViaRealRetrieveTool(): Unit =
    val question = "why must some components be written in Java instead of Scala?"
    val capturedToolContent = new AtomicReference[String]()

    model
      .whenUserMessage((um: UserMessage) => um.content().contains(question))
      .reply(new ToolInvocationRequest("retrieve", s"""{"question":"$question"}"""))
    model
      .whenToolResult((tr: ToolResult) => tr.name() == "retrieve")
      .thenReply { (tr: ToolResult) =>
        capturedToolContent.set(tr.content())
        new AiResponse("Because the Workflow and entity clients are keyed on Java method references.")
      }

    val reply = post(question)

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer)
      .isEqualTo("Because the Workflow and entity clients are keyed on Java method references.")

    // SC-003: the real remote tool ran — its top source is the semantically-correct passage.
    val toolContent = capturedToolContent.get()
    assertThat(toolContent).isNotNull()
    val topSource = JsonSupport.getObjectMapper.readTree(toolContent).get(0).get("source").asText
    assertThat(topSource).isEqualTo("interop-method-ref-wall")

  /** US1 / SC-002: for a question the corpus doesn't cover, the model declines honestly (it retrieved,
    * the passages were weak, so it says it doesn't know) — a `200` with a non-fabricated decline, not
    * an invented answer. */
  @Test
  def outOfCorpusQuestionDeclinesHonestly(): Unit =
    val question = "what is the capital of France?"
    val decline = "I don't know — the knowledge corpus doesn't cover that."

    model
      .whenUserMessage((um: UserMessage) => um.content().contains(question))
      .reply(new ToolInvocationRequest("retrieve", s"""{"question":"$question"}"""))
    model
      .whenToolResult((tr: ToolResult) => tr.name() == "retrieve")
      .thenReply((_: ToolResult) => new AiResponse(decline))

    val reply = post(question)

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(decline)

  // ---- US2 (P2): validation-first ------------------------------------------------------------

  /** US2 / SC-004: a blank/whitespace `question` is rejected `400` **before** any model or tool call.
    * No model response is registered, so if the agent were engaged it would fail (500) — asserting
    * `400` proves validation short-circuited ahead of the model. (No `responseBodyAs`: it throws on a
    * non-2xx status — the httpClient failure-status pattern.) */
  @Test
  def blankQuestionRejectedBeforeAnyCall(): Unit =
    val reply = httpClient
      .POST("/grounded-ask")
      .withRequestBody(McpClientEndpoint.AskRequest(Some("   ")))
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US2 / SC-004: an absent `question` field deserializes to `None` → `400`, not a `500`. */
  @Test
  def absentQuestionRejected(): Unit =
    val reply = httpClient
      .POST("/grounded-ask")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US2 / SC-004: a malformed JSON body is rejected by the SDK boundary → `400`. */
  @Test
  def malformedBodyRejected(): Unit =
    val reply = httpClient
      .POST("/grounded-ask")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{ \"question\": ".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US2 / FR-007: an unknown extra property alongside a valid question is tolerated → `200`. The
    * model answers directly here (a valid choice — it may skip the tool), so no tool scripting. */
  @Test
  def unknownPropertyTolerated(): Unit =
    model.fixedResponse("A grounded answer.")
    val reply = httpClient
      .POST("/grounded-ask")
      .withRequestBody(
        ContentTypes.APPLICATION_JSON,
        """{"question":"how does session memory work?","surprise":"ignored"}""".getBytes
      )
      .responseBodyAs(classOf[McpClientEndpoint.AskReply])
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo("A grounded answer.")

  /** FR-010: when the agent turn fails (e.g. the remote MCP tool is unreachable), the request degrades
    * gracefully — a clean `200` carrying the fixed honest "unavailable" message, NOT a raw `500` and no
    * stack trace to the caller. Simulated by failing the model turn (`failWith`), which the agent's
    * `.onFailure` catches and degrades. (The real cause is logged server-side.) */
  @Test
  def agentTurnFailureDegradesToCleanReply(): Unit =
    val question = "why must some components be written in Java instead of Scala?"
    model
      .whenUserMessage((um: UserMessage) => um.content().contains(question))
      .failWith(new RuntimeException("simulated MCP tool / model failure"))

    val reply = post(question)

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(McpClientAgent.FailureReply)

  // ---- US3 (P3): one corpus, reached two ways ------------------------------------------------

  /** US3 / SC-005: the passages the agent's remote `retrieve` tool surfaces are exactly those a direct
    * `KnowledgeStore.retrieve` returns for the same question — one corpus reached two ways (agentic
    * tool path vs. cap-8's direct DI path). Proven offline because the embeddings are deterministic. */
  @Test
  def mcpToolRetrievalMatchesDirectKnowledgeStore(): Unit =
    val question = "how is conversation history remembered by session id across turns?"
    val capturedToolContent = new AtomicReference[String]()

    model
      .whenUserMessage((um: UserMessage) => um.content().contains(question))
      .reply(new ToolInvocationRequest("retrieve", s"""{"question":"$question"}"""))
    model
      .whenToolResult((tr: ToolResult) => tr.name() == "retrieve")
      .thenReply { (tr: ToolResult) =>
        capturedToolContent.set(tr.content())
        new AiResponse("Grounded from the retrieved passages.")
      }

    post(question) // drives the tool round-trip; the captured content is the assertion target

    val gotSources = JsonSupport.getObjectMapper
      .readTree(capturedToolContent.get())
      .elements()
      .asScala
      .map(_.get("source").asText)
      .toList
    val expectedSources = referenceStore.retrieve(question, TopK).map(_.source)
    assertThat(gotSources.asJava).isEqualTo(expectedSources.asJava)
