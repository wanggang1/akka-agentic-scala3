package com.gwgs.akkaagentic.streaming.api

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.Http
import akka.http.javadsl.model.{ContentTypes, HttpRequest, StatusCodes}
import akka.japi.pf.PFBuilder
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.stream.javadsl.{Sink, Source}
import akka.util.ByteString
import com.fasterxml.jackson.databind.ObjectMapper
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent
import com.gwgs.akkaagentic.streaming.domain.SseErrorReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Tag, Test}

/** US2 — the payoff: a failure is a self-describing `event: error` frame, never a silent empty success.
  *
  * The guards are driven down to ~1s (like cap-14's failure test) so a failure is cheap to observe; the
  * shipped defaults (60s / 30s) are for real callers. `@Tag("slow")` because these wait on timeouts.
  *
  * **What IS and ISN'T provable offline (inherited from cap-14's measurement).** `TestModelProvider`
  * produces the whole reply and tokenizes it afterwards, and a `failWith` turn makes the token stream go
  * *silent* (research Q-A: nothing emitted, never completes, never fails on its own). So:
  *   - a **pre-first-token** failure is fully testable — the endpoint's `initialTimeout` raises a
  *     `TimeoutException`, which the endpoint's `.recover` turns into an `event: error` frame. This is
  *     the direct contrast with cap-14, where the identical scenario yields a silent empty `200`.
  *   - a genuine **mid-stream** failure (model dies after N of M tokens) **cannot be scripted** — no
  *     sleep or `failWith` makes a gap *between* tokens. Rather than fake it over HTTP, the `.recover`
  *     ordering (prior elements delivered, then a final error element) is pinned on a synthetic source
  *     below, exactly as cap-14 pins its `idleTimeout` contract. The HTTP mid-stream case is live-only.
  */
@Tag("slow")
class SseChatFailureIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig(
        """akka.javasdk.agent.googleai-gemini.api-key = n/a
          |streaming.first-token-timeout = 1s
          |streaming.idle-timeout = 1s""".stripMargin
      )
      .withModelProvider(classOf[StreamingChatAgent], model)

  @BeforeEach
  def reset(): Unit = model.reset()

  /** SC-002 — THE capability. A failure before the first token becomes exactly one `event: error`
    * frame, carrying a client-safe reason, and the request ends well within the read bound.
    *
    * The contrast that justifies the whole capability: cap-14's `StreamingChatFailureIntegrationTest`
    * runs the identical scenario and measures a `200 OK` with a body that **completes with zero bytes**
    * — indistinguishable from a successful empty answer. Here the same failure is self-describing. */
  @Test
  def aFailureBeforeTheFirstFragmentBecomesAnErrorFrame(): Unit =
    model.whenMessage((_: String) => true).failWith(new RuntimeException("simulated model failure"))

    val start = System.nanoTime()
    val response = sseRequest("c-fail", "anything")
    val frames = parseFrames(bodyOf(response))
    val elapsedMs = (System.nanoTime() - start) / 1_000_000

    // It ends (the 1s guard, not a 20s hang), and the HTTP status is the committed 200.
    assertThat(elapsedMs).isLessThan(15000L)
    assertThat(response.status()).isEqualTo(StatusCodes.OK)

    // The distinct signal cap-14 could not give: exactly one frame, and it is an error frame.
    assertThat(frames.map(_.event).asJava).containsExactly("error")
    // Offline, a failWith turn is indistinguishable from a model that never responds, so the observed
    // cause is the initialTimeout -> TimedOut reason. A real provider error materialises as a stream
    // failure (~178ms, research Q-A) and would map to Failed; that distinction is live-only.
    assertThat(decodeReason(frames.head.data)).isEqualTo(SseErrorReason.TimedOut)
    // FR-006 — no internal detail leaks.
    assertThat(frames.head.data).doesNotContain("Exception")
    assertThat(frames.head.data).doesNotContain("simulated model failure")

  /** False-positive guard (cap-12's jailbreak-regression spirit): a slow but answering model must still
    * stream its answer as `data` frames with no error frame. A guard that fired on every slow answer
    * would be worse than none. */
  @Test
  def aSlowButAnsweringModelStillStreamsDataFrames(): Unit =
    model.fixedResponse { (_: TestModelProvider.InputMessage) =>
      Thread.sleep(400) // inside the 1s first-token guard
      new TestModelProvider.AiResponse("A short but complete answer.")
    }

    val response = sseRequest("c-slow", "anything")
    val frames = parseFrames(bodyOf(response))

    assertThat(response.status()).isEqualTo(StatusCodes.OK)
    assertThat(frames.size).isGreaterThan(0)
    assertThat(frames.map(_.event).toSet.asJava).containsExactly("data")
    assertThat(frames.map(f => decodeText(f.data)).mkString).isEqualTo("A short but complete answer.")

  /** Edge case — a legitimately empty answer ends cleanly with NO error frame, so an empty success stays
    * distinguishable from a failure (data-model invariant). */
  @Test
  def anEmptySuccessfulAnswerCarriesNoErrorFrame(): Unit =
    model.fixedResponse("")

    val response = sseRequest("c-empty", "anything")
    val frames = parseFrames(bodyOf(response))

    assertThat(response.status()).isEqualTo(StatusCodes.OK)
    assertThat(frames.map(_.event).asJava).doesNotContain("error")

  /** SC-003 (mechanism, offline proxy) — `.recover` preserves prior elements and appends a final error
    * element. Pinned on a synthetic javadsl source using the SAME `PFBuilder` recover the endpoint uses,
    * because a mid-stream gap cannot be produced through the model (see class note). The real HTTP
    * ordering is live-only; this proves the operator does the right thing. */
  @Test
  def recoverDeliversPriorElementsThenAFinalErrorElement(): Unit =
    val data: java.util.List[SseChatEvent] =
      java.util.List.of(new SseChatEvent.Data("first "), new SseChatEvent.Data("second"))

    val recovered: java.util.List[SseChatEvent] =
      Source
        .from(data)
        .concat(Source.failed(new RuntimeException("mid-stream boom")))
        .recover(
          new PFBuilder[Throwable, SseChatEvent]()
            .matchAny(t => new SseChatEvent.ErrorEvent(SseErrorReason.reasonFor(t)))
            .build()
        )
        .runWith(Sink.seq[SseChatEvent], testKit.getMaterializer())
        .toCompletableFuture
        .get(10, TimeUnit.SECONDS)

    val elements = recovered.asScala.toList
    // The stream COMPLETED (runWith returned a list rather than throwing), which already proves recover
    // fired. The mechanism: at least one data element was delivered, every non-final element is a data
    // element, and the stream ends with exactly one error element carrying the generic (non-timeout)
    // reason. The exact data count is left unpinned — concat(Source.failed) cancels the upstream mid-way
    // under fusion, which is a stream-plumbing artifact, not the endpoint's path (whose failure comes
    // from an upstream timeout, not a concat).
    assertThat(elements.size).isGreaterThan(1)
    val last = elements.last
    assertThat(last).isInstanceOf(classOf[SseChatEvent.ErrorEvent])
    assertThat(last.asInstanceOf[SseChatEvent.ErrorEvent].reason).isEqualTo(SseErrorReason.Failed)
    elements.dropRight(1).foreach(e => assertThat(e).isInstanceOf(classOf[SseChatEvent.Data]))
    assertThat(elements.dropRight(1).size).isGreaterThan(0) // some data preceded the error

  // --- helpers (kept local; small, and the US1 test's are private) --------------------------------

  private def sseRequest(sessionId: String, message: String) =
    val mat = testKit.getMaterializer()
    val request = HttpRequest
      .POST(s"http://${testKit.getHost()}:${testKit.getPort()}/sse-chat/$sessionId")
      .withEntity(ContentTypes.APPLICATION_JSON, s"""{"message":"$message"}""")
    Http.get(mat.system).singleRequest(request).toCompletableFuture.get(20, TimeUnit.SECONDS)

  private def bodyOf(response: akka.http.javadsl.model.HttpResponse): String =
    response
      .entity()
      .getDataBytes()
      .runWith(Sink.seq[ByteString], testKit.getMaterializer())
      .toCompletableFuture
      .get(20, TimeUnit.SECONDS)
      .asScala
      .foldLeft(ByteString.empty)(_ ++ _)
      .utf8String

  private def parseFrames(body: String): List[SseChatFailureIntegrationTest.Frame] =
    body
      .split("\n\n")
      .toList
      .flatMap { block =>
        val lines = block.split("\n").toList.map(_.stripLineEnd)
        val event = lines.collectFirst { case l if l.startsWith("event:") => l.stripPrefix("event:").trim }
        val data = lines.collectFirst { case l if l.startsWith("data:") => l.stripPrefix("data:").trim }
        (event, data) match
          case (Some(e), Some(d)) => Some(SseChatFailureIntegrationTest.Frame(e, d))
          case _                  => None
      }

  private val mapper = new ObjectMapper()
  private def decodeText(dataPayload: String): String = mapper.readTree(dataPayload).get("text").asText()
  private def decodeReason(dataPayload: String): String = mapper.readTree(dataPayload).get("reason").asText()

object SseChatFailureIntegrationTest:
  final case class Frame(event: String, data: String)
