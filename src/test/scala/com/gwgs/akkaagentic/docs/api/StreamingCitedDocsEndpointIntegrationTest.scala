package com.gwgs.akkaagentic.docs.api

import java.util.Optional
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.Http
import akka.http.javadsl.model.{ContentTypes, HttpRequest, MediaTypes, StatusCodes}
import akka.japi.Pair
import akka.japi.pf.PFBuilder
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.stream.javadsl.{Sink, Source}
import akka.util.ByteString
import com.fasterxml.jackson.databind.ObjectMapper
import com.gwgs.akkaagentic.docs.application.StreamingCitedDocsAgent
import com.gwgs.akkaagentic.docs.domain.CitationMarkers
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
      // A short first-token timeout so the pre-first-token failure test (a hanging model — cap-16 proved
      // failWith on a streamed agent hangs rather than surfacing a failure) fails fast via the endpoint's
      // initialTimeout guard. Scripted happy/decline responses produce their first token instantly, well
      // inside this bound, so it does not affect them.
      .withAdditionalConfig("streaming.first-token-timeout = 3s")
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

  /** US2 / SC-003 / FR-007 — an honest decline streams the decline prose and cites nothing, while the
    * retrieved set is still shown (so the decline is visible, not a silent empty frame). */
  @Test
  def aDeclineStreamsTheDeclineAndCitesNothing(): Unit =
    model.reset()
    model.fixedResponse("I don't know") // the DocsAgent.DontKnow sentinel — no markers

    val frames = parseFrames(bodyOf(sseRequest("what is the airspeed velocity of an unladen swallow?")))

    // The clean prose is the decline itself.
    val reassembled = frames.filter(_.event == "data").map(f => decodeText(f.data)).mkString
    assertThat(reassembled).isEqualTo("I don't know")

    // Exactly one citations frame; usedSources empty; retrievedSources still populated.
    assertThat(frames.last.event).isEqualTo("citations")
    val (used, retrieved) = citations(frames.last.data)
    assertThat(used.asJava).isEmpty()
    assertThat(retrieved.asJava).isNotEmpty()

  /** US2 / FR-008 — a non-decline answer that reports only an OUT-OF-RANGE marker cites nothing (the floor
    * drops a number that was never presented), while the retrieved set is still shown. No fabricated
    * source ever appears. */
  @Test
  def anOutOfRangeMarkerCitesNothingButShowsRetrieved(): Unit =
    model.reset()
    // Only 3 passages are retrieved, so [9] was never presented — the floor must drop it.
    model.fixedResponse("Agent work is coordinated by a durable process [9].")

    val frames = parseFrames(bodyOf(sseRequest("how is agent work coordinated?")))

    val reassembled = frames.filter(_.event == "data").map(f => decodeText(f.data)).mkString
    assertThat(reassembled).doesNotContain("[") // marker stripped from prose regardless

    val (used, retrieved) = citations(frames.last.data)
    assertThat(used.asJava).isEmpty() // out-of-range number resolves to nothing (honesty floor)
    assertThat(retrieved.asJava).isNotEmpty()
    assertThat(retrieved.asJava).doesNotContain("9") // never a fabricated source

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

  /** US3 / T015 — the load-bearing failure mechanics, MEASURED directly on the real operators rather than
    * assumed. A model failure after N tokens is not materializable with TestModelProvider (cap-16 proved
    * failWith on a streamed agent hangs, not fails), so this probe runs the endpoint's exact pipeline shape
    * — the real [[CitationMarkers]] stripper, `concatLazy` to a lazy citations source, `.recover` to a
    * terminal element — over an upstream that emits a chunk holding a partial marker (`"hello [1"`) and
    * then fails. It answers two questions the endpoint's correctness rests on:
    *   1. does `statefulMap`'s completion hook flush the held carry (`"[1"`) on a FAILURE, leaking a frame
    *      after the point of failure? (plan risk — it must not)
    *   2. does `concatLazy` subscribe the citations source when the data source FAILED? (research R5 — it
    *      must not, so a failed stream emits no citations frame) */
  @Test
  def aFailureAfterAPartialMarkerFlushesNoCarryAndEmitsNoCitations(): Unit =
    val data = Source
      .single("hello [1")
      .concat(Source.failed[String](new RuntimeException("boom")))
      .statefulMap[CitationMarkers.State, String](
        () => CitationMarkers.empty,
        (carry, chunk) => { val r = CitationMarkers.step(carry, chunk); Pair.create(r.carry, r.emit) },
        carry => { val f = CitationMarkers.finish(carry); Optional.of(f.emit) }
      )
      .filter((s: String) => !s.isEmpty)
      .map((s: String) => "D:" + s)

    val citations = Source.lazySource(() => Source.single("CITATIONS"))

    val out = data
      .concatLazy(citations)
      .recover(new PFBuilder[Throwable, String]().matchAny(_ => "ERR").build())
      .runWith(Sink.seq[String], testKit.getMaterializer())
      .toCompletableFuture
      .get(10, TimeUnit.SECONDS)
      .asScala
      .toList

    // MEASURED: the two properties T015 rests on hold decisively — no leaked "[1" carry (statefulMap does
    // NOT flush its completion hook on a failure) and no CITATIONS (concatLazy does NOT subscribe its
    // second source when the first failed), and the stream ends with the terminal error element.
    //
    // NOTE (honest limitation): a synthetic `Source.single(...).concat(Source.failed)` fails essentially
    // synchronously with no network flush, so the already-processed clean prefix ("D:hello ") is dropped
    // in-memory rather than delivered — the observed output here is just ["ERR"]. Over real SSE the earlier
    // data frames are network-flushed before the later failure, which the HTTP-level tests exercise; this
    // probe is only reliable for the no-leak / no-citations / terminal-error invariants below.
    assertThat(out.filter(_.startsWith("D:[")).asJava).isEmpty() // no carry/partial-marker flush on failure
    assertThat(out.asJava).doesNotContain("CITATIONS") // concatLazy skips citations on a failed data source
    assertThat(out.last).isEqualTo("ERR") // the failure becomes the terminal element via .recover

  /** US3 / FR-009 / FR-010 — a failure before the first token ends the stream with a terminal
    * `event: error` carrying a client-safe reason and NO `citations` frame. Materialized offline by a
    * hanging model caught by the endpoint's `initialTimeout` (see the short timeout in testKitSettings). */
  @Test
  def aPreFirstTokenFailureEndsWithAnErrorFrameAndNoCitations(): Unit =
    model.reset()
    model.whenMessage(_ => true).failWith(new RuntimeException("simulated model failure"))

    val frames = parseFrames(bodyOf(sseRequest("anything at all")))

    // Terminal error frame; no citations frame ever follows a failure (FR-009).
    assertThat(frames.last.event).isEqualTo("error")
    assertThat(frames.map(_.event).asJava).doesNotContain("citations")

    // The reason is client-safe: no exception message, no class name (FR-010 / cap-20's SseErrorReason).
    val reason = mapper.readTree(frames.last.data).get("reason").asText()
    assertThat(reason).doesNotContain("simulated model failure")
    assertThat(reason).doesNotContain("Exception")
    assertThat(reason).isNotEmpty()

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
