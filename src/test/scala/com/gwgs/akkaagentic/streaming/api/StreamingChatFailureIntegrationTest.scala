package com.gwgs.akkaagentic.streaming.api

import java.time.Duration
import java.util.concurrent.TimeUnit

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import akka.http.javadsl.Http
import akka.http.javadsl.model.{ContentTypes, HttpRequest}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.stream.javadsl.{Sink, Source}
import akka.util.ByteString
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Tag, Test}
import org.slf4j.LoggerFactory

/** US3 — an interrupted answer must not be passed off as a complete one, and a caller must never wait
  * for ever.
  *
  * Differs from the other streaming tests in two config keys: the guards are driven down to ~1s so a
  * failure is cheap to observe. The shipped defaults (60s / 30s) are sized for real callers.
  *
  * **T017 — what is NOT provable offline, stated here rather than faked.** `TestModelProvider`
  * produces the whole reply and tokenizes it afterwards, so every fragment flows at once and an
  * injected failure always lands *before* the first token. That means:
  *   - a **pre-token** failure is fully testable, and is `aFailureBeforeTheFirstFragmentTerminates`;
  *   - a genuine **mid-stream** failure (the model dying after 20 of 57 tokens) **cannot be scripted
  *     at all** — no sleep or `failWith` can produce a gap *between* tokens. Rather than write a test
  *     that pretends to cover it, the `idleTimeout` contract FR-005 relies on is pinned directly on a
  *     synthetic source below, and the real case is live-only work (research, "What remains
  *     unverified"). An SDK that let a test provider emit tokens on demand would close this gap.
  */
@Tag("slow")
class StreamingChatFailureIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(classOf[StreamingChatFailureIntegrationTest])

  private val model = new TestModelProvider()

  private val Answer = "A short but complete answer, delivered in fragments."

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

  private def streamOf(sessionId: String, message: String) =
    val mat = testKit.getMaterializer()
    val request = HttpRequest
      .POST(s"http://${testKit.getHost()}:${testKit.getPort()}/stream-chat/$sessionId")
      .withEntity(ContentTypes.APPLICATION_JSON, s"""{"message":"$message"}""")

    val response = Http.get(mat.system).singleRequest(request).toCompletableFuture.get(20, TimeUnit.SECONDS)
    val body = Try(
      response
        .entity()
        .getDataBytes()
        .runWith(Sink.seq[ByteString], mat)
        .toCompletableFuture
        .get(20, TimeUnit.SECONDS)
        .asScala
        .toList
    )
    (response, body)

  /** FR-006 — the case this capability was nearly shipped without noticing. Measured in research
    * Q-A(2): when the model fails before the first token, the SDK's token stream emits nothing,
    * completes never and fails never — still silent after **240 seconds**. The endpoint's
    * `initialTimeout` is what turns that into a terminated request.
    *
    * The assertion is the **bound**: without the guard this test would hang until the harness killed
    * it. What the caller sees at the HTTP level is *recorded* rather than demanded, because the status
    * line may already be on the wire by the time the stream fails — that is a property of streaming,
    * not something this capability gets to choose.
    */
  @Test
  def aFailureBeforeTheFirstFragmentTerminatesWithinTheGuard(): Unit =
    model.whenMessage((_: String) => true).failWith(new RuntimeException("simulated model failure"))

    val start = System.nanoTime()
    val (response, body) = streamOf("c-fail", "anything")
    val elapsedMs = (System.nanoTime() - start) / 1_000_000

    val observed = body match
      case Success(chunks) => s"body completed with ${chunks.size} chunk(s): ${chunks.map(_.utf8String).mkString}"
      case Failure(t) =>
        Iterator
          .iterate(t: Throwable)(_.getCause)
          .takeWhile(_ != null)
          .map(e => s"${e.getClass.getName}: ${e.getMessage}")
          .mkString(" <- ")
    logger.info("US3 >>> status = {}, after {} ms, body = {}", response.status(), elapsedMs, observed)

    // The claim: it ENDS. A 1s first-token guard must terminate the request in well under the 20s
    // read bound — and without the guard, not at all.
    assertThat(elapsedMs).isLessThan(15000L)

    // The MEASURED shape, pinned rather than left as a vague disjunction — and it is not what one
    // would hope for. The caller gets `200 OK` with a body that **completes normally and carries zero
    // chunks**: the status line was already on the wire before the stream failed, and Akka HTTP ends
    // the (already committed) chunked body rather than aborting it. So at the HTTP level a pre-token
    // failure is indistinguishable from "the assistant had nothing to say" unless a caller treats an
    // empty body as failure. FR-006 is satisfied in the sense that matters most — the request ends
    // instead of hanging for ever — but NOT in the sense of carrying a distinct signal.
    //
    // Pinned here because a change in this shape is a finding to record, not a test to update.
    assertThat(response.status().intValue()).isEqualTo(200)
    // NB: no `describedAs` — AssertJ returns `SELF`, which scalac will not chain from (the same
    // friction as in the endpoint test). The intent: the already-committed body COMPLETES rather
    // than aborting, which is exactly why this case carries no failure signal.
    assertThat(body.isSuccess).isTrue()
    assertThat(body.get.map(_.utf8String).mkString).isEmpty()

    // Whatever else is true, it must not look like the finished answer.
    assertThat(body.get.map(_.utf8String).mkString).isNotEqualTo(Answer)

  /** The false-positive guard, in the spirit of capability 12's jailbreak regression test: a model
    * that is slow but *does* answer must still succeed. A guard that fires on every slow answer would
    * be worse than no guard, and this is the test that would catch a badly-chosen default. */
  @Test
  def aSlowButAnsweringModelStillCompletes(): Unit =
    // 400ms of thinking, comfortably inside the 1s first-token guard.
    model.fixedResponse { (_: TestModelProvider.InputMessage) =>
      Thread.sleep(400)
      new TestModelProvider.AiResponse(Answer)
    }

    val (response, body) = streamOf("c-slow", "anything")

    assertThat(response.status().intValue()).isEqualTo(200)
    assertThat(body.isSuccess).isTrue()
    assertThat(body.get.map(_.utf8String).mkString).isEqualTo(Answer)

  /** FR-005's mechanism, pinned where it can actually be observed.
    *
    * The endpoint composes `idleTimeout` so that a stream which stalls *after* delivering fragments
    * ends rather than dangling. The model cannot produce that gap offline (see the class note), so the
    * operator's contract is asserted on a synthetic source instead: elements delivered before the
    * stall are genuinely delivered, and the stream then **fails** rather than completing — which is
    * what makes a truncated answer distinguishable from a finished one.
    */
  @Test
  def idleTimeoutDeliversWhatArrivedThenFailsRatherThanCompleting(): Unit =
    val delivered = ListBuffer.empty[String]
    val stalling = Source
      .single("first fragment ")
      .concat(Source.maybe[String]) // never completes: the stall
      .map { fragment =>
        delivered += fragment
        fragment
      }
      .idleTimeout(Duration.ofMillis(500))

    val start = System.nanoTime()
    val outcome = Try(
      stalling
        .runWith(Sink.seq[String], testKit.getMaterializer())
        .toCompletableFuture
        .get(10, TimeUnit.SECONDS)
    )
    val elapsedMs = (System.nanoTime() - start) / 1_000_000

    // It ended, and it ended by failing — not by completing successfully.
    assertThat(outcome.isFailure).isTrue()
    assertThat(elapsedMs).isLessThan(5000L)

    // What arrived before the stall really did arrive: a truncated answer keeps its earlier text.
    assertThat(delivered.toList.mkString).isEqualTo("first fragment ")

    logger.info("US3 >>> idleTimeout failed after {} ms having delivered {}", elapsedMs, delivered.toList)
