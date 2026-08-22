package com.gwgs.akkaagentic.mcp.api

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.model.ContentTypes
import akka.javasdk.JsonSupport
import akka.javasdk.testkit.TestKitSupport
import com.fasterxml.jackson.databind.JsonNode
import com.gwgs.akkaagentic.docs.application.KnowledgeStore
import com.gwgs.akkaagentic.docs.domain.KnowledgeCorpus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Drives the [[KnowledgeMcpEndpoint]] MCP server over JSON-RPC at `/mcp` using the testkit HTTP client
  * (there is no MCP-specific testkit; hand-crafted JSON-RPC payloads are the SDK's prescribed approach —
  * research R4). Everything is offline: no model, no network.
  *
  * Envelope pinned in T004 (the one empirical unknown): the stateless Streamable HTTP transport needs
  * **no `initialize` handshake** — `tools/list`/`tools/call` work directly — and replies with a **plain
  * `application/json`** single JSON-RPC object (NOT `text/event-stream`), status `200`. So a request is
  * just a POST of the JSON-RPC body with an `Accept` header, and the reply body is parsed as JSON.
  *
  * The retrieval half is **deterministic** (in-process embeddings, fixed corpus), so ground truth is a
  * direct call to a second [[KnowledgeStore.fromCorpus]] built here — identical to the one the endpoint
  * injects. That's what lets SC-004 (MCP == cap-8 retrieval) be asserted offline, with no model.
  *
  * The tool takes a single `question` param and returns a fixed top-K (3), mirroring cap-8. A tunable
  * `maxResults` was designed but is blocked by an SDK-3.6.0 bug (see [[KnowledgeMcpEndpoint]] scaladoc);
  * its clamping tests are removed until the SDK is upgraded.
  */
class KnowledgeMcpEndpointIntegrationTest extends TestKitSupport:

  private val AcceptBoth = "application/json, text/event-stream"
  private val mapper = JsonSupport.getObjectMapper

  /** cap-8's fixed retrieval size, mirrored by the tool. */
  private val TopK = 3

  /** Ground-truth retrieval, deterministic and identical to the endpoint's injected store. */
  private lazy val referenceStore = KnowledgeStore.fromCorpus()

  /** POST a raw JSON-RPC body to `/mcp` and return (status, body-as-utf8). */
  private def mcp(jsonRpc: String): (Int, String) =
    val res = httpClient
      .POST("/mcp")
      .addHeader("Accept", AcceptBoth)
      .withRequestBody(ContentTypes.APPLICATION_JSON, jsonRpc.getBytes)
      .invoke()
    (res.status().intValue(), res.body().utf8String)

  /** Call the `retrieve` tool and return its JSON-RPC `result` node (the MCP tool result). */
  private def callRetrieve(argumentsJson: String, id: Int = 2): JsonNode =
    val body =
      s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"retrieve","arguments":$argumentsJson}}"""
    val (status, resp) = mcp(body)
    assertThat(status).isEqualTo(200)
    mapper.readTree(resp).get("result")

  private def isError(result: JsonNode): Boolean = result.get("isError").asBoolean

  /** The single text content block of a tool result. */
  private def textOf(result: JsonNode): String = result.get("content").get(0).get("text").asText

  /** Parse a successful tool result's text (a JSON array of `{source,score,text}`) into its nodes. */
  private def passages(result: JsonNode): List[JsonNode] =
    mapper.readTree(textOf(result)).elements().asScala.toList

  private def sourcesOf(result: JsonNode): List[String] = passages(result).map(_.get("source").asText)
  private def scoresOf(result: JsonNode): List[Double] = passages(result).map(_.get("score").asDouble)

  /** JSON-string-quote a value for embedding in a hand-built JSON-RPC body. */
  private def quote(s: String): String = mapper.writeValueAsString(s)

  // ---- SC-001: discovery ----------------------------------------------------------------------

  /** An MCP client discovers the `retrieve` tool via `tools/list`, and the reflected input schema has a
    * required `question` — proving the Scala `@McpEndpoint` is reachable over real JSON-RPC and the bare
    * param bound correctly (research R2). */
  @Test
  def toolsListAdvertisesRetrieve(): Unit =
    val (status, body) = mcp("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
    assertThat(status).isEqualTo(200)
    assertThat(body).contains(""""name":"retrieve"""")
    assertThat(body).contains("Semantic search over the knowledge corpus")

    val schema = mapper.readTree(body).get("result").get("tools").get(0).get("inputSchema")
    assertThat(schema.get("properties").has("question")).isTrue
    val required = schema.get("required").elements().asScala.map(_.asText).toList.asJava
    assertThat(required).contains("question")

  // ---- SC-002 / SC-004: semantic retrieval, grounded & parity ---------------------------------

  /** SC-002: a **paraphrased** in-corpus question (no keywords shared with the source passage) still
    * ranks the semantically-correct passage first, and results come back score-descending. */
  @Test
  def paraphrasedQuestionRanksCorrectPassageFirstScoreDescending(): Unit =
    // "why must some components be written in Java instead of Scala?" -> the method-ref-wall passage,
    // which shares no wording with the query (it talks about dynamicCall / method references).
    val result = callRetrieve(
      """{"question":"why must some components be written in Java instead of Scala?"}"""
    )
    assertThat(isError(result)).isFalse
    assertThat(sourcesOf(result).head).isEqualTo("interop-method-ref-wall")
    val scores = scoresOf(result)
    assertThat(scores).isEqualTo(scores.sorted(Ordering[Double].reverse))

  /** SC-004: for the same question and the fixed K, the MCP tool returns exactly the sources, order, and
    * scores that cap-8's own `KnowledgeStore.retrieve` produces — the same store behind both surfaces. */
  @Test
  def mcpRetrievalMatchesDirectKnowledgeStore(): Unit =
    val question = "how is conversation history remembered by session id across turns?"
    val result = callRetrieve(s"""{"question":${quote(question)}}""")

    val reference = referenceStore.retrieve(question, TopK)
    assertThat(sourcesOf(result)).isEqualTo(reference.map(_.source))
    // scores match within a tiny tolerance (double round-trip through JSON)
    scoresOf(result).zip(reference.map(_.score)).foreach { case (got, expected) =>
      assertThat(got).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6))
    }

  // ---- fixed top-K -----------------------------------------------------------------------------

  /** The tool returns the fixed top-K (3), matching cap-8's `DocsEndpoint`. */
  @Test
  def retrieveReturnsFixedTopK(): Unit =
    val result = callRetrieve("""{"question":"how do agents remember conversations?"}""")
    assertThat(isError(result)).isFalse
    assertThat(passages(result).size).isEqualTo(TopK)

  // ---- M1: out-of-corpus question ------------------------------------------------------------

  /** An out-of-corpus question does NOT error — the retrieve tool always returns its top-K passages
    * (declining is cap-8's *agent* concern, not this tool's). But its best match scores strictly lower
    * than a strongly in-corpus question, documenting that retrieval is genuinely weak off-corpus. */
  @Test
  def outOfCorpusQuestionReturnsWeakButNonErrorResults(): Unit =
    val offCorpus = callRetrieve("""{"question":"what is the recipe for chocolate chip cookies?"}""")
    assertThat(isError(offCorpus)).isFalse
    assertThat(passages(offCorpus).nonEmpty).isTrue

    val inCorpus = callRetrieve(
      """{"question":"why must some components be written in Java instead of Scala?"}"""
    )
    assertThat(scoresOf(offCorpus).head).isLessThan(scoresOf(inCorpus).head)

  // ---- FR-005: validation failure ------------------------------------------------------------

  /** A blank question is a tool error (`isError: true`) carrying cap-8's validation message, and **no
    * retrieval runs** — the error text is the plain message, not a passage array. */
  @Test
  def blankQuestionIsToolErrorWithNoRetrieval(): Unit =
    val result = callRetrieve("""{"question":"   "}""", id = 4)
    assertThat(isError(result)).isTrue
    val text = textOf(result)
    assertThat(text).contains("question must not be blank")
    assertThat(text.trim).doesNotStartWith("[") // not a passages array -> retrieval never ran

  // ---- US2 (P2): corpus as an MCP resource ----------------------------------------------------

  /** `resources/list` advertises the corpus-sources resource with its URI, name, and JSON mime type —
    * proving a Scala `@McpResource` (zero-arg static resource) is discoverable over JSON-RPC. */
  @Test
  def resourcesListAdvertisesCorpusSources(): Unit =
    val (status, body) = mcp("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}""")
    assertThat(status).isEqualTo(200)
    assertThat(body).contains("knowledge://corpus/sources")
    assertThat(body).contains("Knowledge corpus sources")
    assertThat(body).contains("application/json")

  /** `resources/read` on the corpus URI returns the JSON array of **all** corpus source labels — exactly
    * `KnowledgeCorpus.passages.map(_.source)`, in order (ground truth, no model). */
  @Test
  def resourcesReadReturnsCorpusSourceLabels(): Unit =
    val (status, body) = mcp(
      """{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"knowledge://corpus/sources"}}"""
    )
    assertThat(status).isEqualTo(200)
    val text = mapper.readTree(body).get("result").get("contents").get(0).get("text").asText
    val labels = mapper.readTree(text).elements().asScala.map(_.asText).toList
    assertThat(labels.asJava).isEqualTo(KnowledgeCorpus.passages.map(_.source).asJava)
