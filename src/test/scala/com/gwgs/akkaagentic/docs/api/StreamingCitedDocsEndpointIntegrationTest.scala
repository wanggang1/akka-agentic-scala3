package com.gwgs.akkaagentic.docs.api

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.Http
import akka.http.javadsl.model.{ContentTypes, HttpRequest, MediaTypes, StatusCodes}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.stream.javadsl.Sink
import akka.util.ByteString
import com.fasterxml.jackson.databind.ObjectMapper
import com.gwgs.akkaagentic.docs.application.StreamingCitedDocsAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Capability 22 (fork B5), US1 — the `POST /sse-cited-ask` happy path, driven end to end with a scripted
  * model over real retrieval.
  *
  * **This test is Scala** (the wall claims only the endpoint, which holds the `tokenStream` method
  * reference; the test holds none — cap-11/20's shape). Frames are read over raw Akka HTTP and the SSE
  * framing is parsed explicitly, so the assertions are about `event:`/`data:` lines.
  *
  * The scripted answer carries inline `[1]` and `[2]` markers — the only citation signal a streamed turn
  * has (research R1). The test proves the two things that make this capability: the markers are stripped
  * from the prose the caller reads (the clean text reassembles), and they reappear — floored against what
  * was retrieved — in exactly one terminal `citations` frame.
  */
class StreamingCitedDocsEndpointIntegrationTest extends TestKitSupport:
  import StreamingCitedDocsEndpointIntegrationTest.*

  private val model = new TestModelProvider()

  /** A grounded answer with two inline markers. After stripping, the prose is the answer minus `[1]`/`[2]`
    * (surrounding spaces kept); the reported numbers are 1 and 2. */
  private val Answer =
    "Agent work survives a restart because the runtime persists the task [1], " +
      "and the agent's process state is replayed on recovery [2]."

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[StreamingCitedDocsAgent], model)

  @BeforeEach
  def reset(): Unit =
    model.reset()
    model.fixedResponse(Answer)

  /** SC-001 + FR-004 + FR-005 + SC-002 — the happy path: `text/event-stream`; the decoded `data` text
    * reassembles to the answer with every `[n]` removed; exactly one terminal `citations` frame follows
    * all data frames; and `usedSources ⊆ retrievedSources`, matching the scripted markers. */
  @Test
  def streamsCleanProseThenOneHonestCitationsFrame(): Unit =
    val response = sseRequest("what makes agent work survive a restart?")

    assertThat(response.status()).isEqualTo(StatusCodes.OK)
    assertThat(response.entity().getContentType().mediaType).isEqualTo(MediaTypes.TEXT_EVENT_STREAM)

    val frames = parseFrames(bodyOf(response))

    // FR-005: exactly one citations frame, and it is the LAST frame (after all data frames). No error.
    assertThat(frames.map(_.event).count(_ == "citations")).isEqualTo(1)
    assertThat(frames.last.event).isEqualTo("citations")
    assertThat(frames.map(_.event).asJava).doesNotContain("error")

    // SC-001 + FR-004: the clean prose reassembles to the answer with the [n] markers removed.
    val dataFrames = frames.filter(_.event == "data")
    val reassembled = dataFrames.map(f => decodeText(f.data)).mkString
    assertThat(reassembled).isEqualTo(Answer.replaceAll("\\[\\d+\\]", ""))
    assertThat(reassembled).doesNotContain("[")

    // SC-002 + FR-005: the floored citations — a subset of retrieved, matching the scripted markers [1],[2].
    val (used, retrieved) = citations(frames.last.data)
    assertThat(retrieved.asJava).isNotEmpty()
    assertThat(retrieved.asJava).containsAll(used.asJava) // used ⊆ retrieved (honesty floor)
    assertThat(used.asJava).containsExactly(retrieved(0), retrieved(1)) // markers 1,2 → first two presented
    assertThat(used.size).isLessThan(retrieved.size) // usage-accurate: fewer than cap-8 would cite

  /** FR-012 — a blank question is rejected before any retrieval or model call, as an ordinary non-streamed
    * 400. No `responseBodyAs` (it throws on non-2xx — the project's httpClient failure-status pattern). */
  @Test
  def aBlankQuestionIsRejectedBeforeAnyStream(): Unit =
    val response = httpClient
      .POST("/sse-cited-ask")
      .withRequestBody(Body("   "))
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)
    assertThat(response.body().utf8String).contains("question must not be blank")

  /** An absent `question` deserializes to `None` → the same 400, not a 500 (feature-003 Option boundary). */
  @Test
  def anAbsentQuestionIsRejectedTheSameWay(): Unit =
    val response = httpClient.POST("/sse-cited-ask").withRequestBody("{}").invoke()
    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  // --- helpers -------------------------------------------------------------------------------------

  private def sseRequest(question: String) =
    val materializer = testKit.getMaterializer()
    val request = HttpRequest
      .POST(s"http://${testKit.getHost()}:${testKit.getPort()}/sse-cited-ask")
      .withEntity(ContentTypes.APPLICATION_JSON, s"""{"question":"$question"}""")
    Http
      .get(materializer.system)
      .singleRequest(request)
      .toCompletableFuture
      .get(30, TimeUnit.SECONDS)

  private def bodyOf(response: akka.http.javadsl.model.HttpResponse): String =
    response
      .entity()
      .getDataBytes()
      .runWith(Sink.seq[ByteString], testKit.getMaterializer())
      .toCompletableFuture
      .get(30, TimeUnit.SECONDS)
      .asScala
      .foldLeft(ByteString.empty)(_ ++ _)
      .utf8String

  /** Parse an SSE body into (event, data) pairs; frames separated by a blank line. */
  private def parseFrames(body: String): List[Frame] =
    body
      .split("\n\n")
      .toList
      .flatMap { block =>
        val lines = block.split("\n").toList.map(_.stripLineEnd)
        val event = lines.collectFirst { case l if l.startsWith("event:") => l.stripPrefix("event:").trim }
        val data = lines.collectFirst { case l if l.startsWith("data:") => l.stripPrefix("data:").trim }
        (event, data) match
          case (Some(e), Some(d)) => Some(Frame(e, d))
          case _                  => None
      }

  private val mapper = new ObjectMapper()

  /** Decode the `text` field of a `data:` payload, which is JSON `{"text":"…"}`. */
  private def decodeText(dataPayload: String): String =
    mapper.readTree(dataPayload).get("text").asText()

  /** Decode a `citations:` payload `{"usedSources":[…],"retrievedSources":[…]}` into the two lists. */
  private def citations(dataPayload: String): (List[String], List[String]) =
    val node = mapper.readTree(dataPayload)
    val used = node.get("usedSources").elements().asScala.map(_.asText()).toList
    val retrieved = node.get("retrievedSources").elements().asScala.map(_.asText()).toList
    (used, retrieved)

object StreamingCitedDocsEndpointIntegrationTest:
  /** The endpoint's wire shape, restated so a change to the Java record surfaces here as a compile error. */
  final case class Body(question: String)

  final case class Frame(event: String, data: String)
