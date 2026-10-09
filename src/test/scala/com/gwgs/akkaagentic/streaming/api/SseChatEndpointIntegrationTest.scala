package com.gwgs.akkaagentic.streaming.api

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.Http
import akka.http.javadsl.model.{ContentTypes, HttpRequest, MediaTypes, StatusCodes}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.stream.javadsl.Sink
import akka.util.ByteString
import com.fasterxml.jackson.databind.ObjectMapper
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** US1 — capability 20's SSE surface over HTTP, driven end to end with a scripted model.
  *
  * **This test is Scala** (the wall claims only the endpoint, which holds the `tokenStream` method
  * reference; the test holds none — cap-11's shape). Written before `SseChatEndpoint` exists, so it
  * fails first (Constitution III).
  *
  * The frames are read over **raw Akka HTTP**, not `httpClient`: a `StrictResponse` buffers the body
  * into one string, which is fine for reassembly but we also parse the SSE framing explicitly so the
  * assertions are about `event:`/`data:` lines, not just bytes.
  */
class SseChatEndpointIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  /** Multi-sentence **and contains an embedded newline** on purpose: one exact-equality assertion on
    * the reassembled text then covers both SC-001 (content parity) and FR-007 (a newline survives the
    * JSON `data:` framing byte-for-byte — if framing corrupted it, the concatenation would differ). */
  private val Answer =
    "The runtime persists the task and the agent's process state as the loop runs.\n" +
      "That is why work survives a restart without any persistence code of your own."

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[StreamingChatAgent], model)

  @BeforeEach
  def reset(): Unit =
    model.reset()
    model.fixedResponse(Answer)

  /** SC-001 + FR-002 + FR-005 + FR-007 — the happy path: the response is `text/event-stream`, every
    * frame is `event: data`, there is no `event: error` (clean completion), and the decoded `text`
    * fields concatenate to exactly the answer, newline and all. */
  @Test
  def theAnswerArrivesAsDataFramesThatReassembleExactly(): Unit =
    val response = sseRequest("c-happy", "why does work survive a restart?")

    assertThat(response.status()).isEqualTo(StatusCodes.OK)
    assertThat(response.entity().getContentType().mediaType)
      .isEqualTo(MediaTypes.TEXT_EVENT_STREAM)

    val frames = parseFrames(bodyOf(response))

    // FR-002 / FR-005: every frame is a data frame; none is an error frame.
    assertThat(frames.map(_.event).toSet.asJava).containsExactly("data")

    // SC-001 + FR-007: decoded text, concatenated in order, is byte-for-byte the answer (incl. the \n).
    val reassembled = frames.map(f => decodeText(f.data)).mkString
    assertThat(reassembled).isEqualTo(Answer)

  /** FR-003 — a blank question is rejected before any model call, as an ordinary non-streamed 400.
    * No `responseBodyAs` (it throws on non-2xx — the project's httpClient failure-status pattern). */
  @Test
  def aBlankQuestionIsRejectedBeforeAnyModelCall(): Unit =
    val response = httpClient
      .POST("/sse-chat/c-blank")
      .withRequestBody(SseChatEndpointIntegrationTest.Body("   "))
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)
    assertThat(response.body().utf8String).contains("question must not be blank")

  /** An absent `message` is the same rejection rather than a 500 — the feature-003 Option boundary. */
  @Test
  def anAbsentMessageIsRejectedTheSameWay(): Unit =
    val response = httpClient
      .POST("/sse-chat/c-absent")
      .withRequestBody("{}")
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  // --- helpers -------------------------------------------------------------------------------------

  private def sseRequest(sessionId: String, message: String) =
    val materializer = testKit.getMaterializer()
    val request = HttpRequest
      .POST(s"http://${testKit.getHost()}:${testKit.getPort()}/sse-chat/$sessionId")
      .withEntity(ContentTypes.APPLICATION_JSON, s"""{"message":"$message"}""")
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

  /** Parse an SSE body into (event, data) pairs. Frames are separated by a blank line; within a frame
    * we read the `event:` and `data:` fields and ignore anything else (e.g. an empty `id` line from the
    * no-op id function, or a `:` heartbeat comment). */
  private def parseFrames(body: String): List[SseChatEndpointIntegrationTest.Frame] =
    body
      .split("\n\n")
      .toList
      .flatMap { block =>
        val lines = block.split("\n").toList.map(_.stripLineEnd)
        val event = lines.collectFirst { case l if l.startsWith("event:") => l.stripPrefix("event:").trim }
        val data = lines.collectFirst { case l if l.startsWith("data:") => l.stripPrefix("data:").trim }
        (event, data) match
          case (Some(e), Some(d)) => Some(SseChatEndpointIntegrationTest.Frame(e, d))
          case _                  => None
      }

  private val mapper = new ObjectMapper()

  /** Decode the `text` field of a `data:` payload, which is JSON `{"text":"…"}` (research Q-D). */
  private def decodeText(dataPayload: String): String =
    mapper.readTree(dataPayload).get("text").asText()

object SseChatEndpointIntegrationTest:
  /** The endpoint's wire shape, restated on the test side so a change to the Java record surfaces here
    * as a compile error. */
  final case class Body(message: String)

  final case class Frame(event: String, data: String)
