package com.gwgs.akkaagentic.compaction.application

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.TestModelProvider.{AiResponse, InputMessage}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T026 / User Story 3 — **research R-5, the one risk the service-wide decision creates.**
  *
  * FR-014 applies compaction to every session in the service, so capability 14's *streamed* chat gets the
  * bound without being modified. That is the one surface where our write races something the SDK is doing
  * for us: a stream is assembled over time, and a compaction replaces the very history it is assembling
  * into. Nothing in Phase 0 measured it, and the plan said to measure it here and — if the race could not
  * be made deterministic — to **say so rather than assert safety**.
  *
  * ==How the race is produced==
  * The summariser is made slow (3 s). Streamed turns are driven until one crosses the threshold, which
  * starts a compaction; while that compaction is inside the slow summariser, another streamed turn is
  * started on the same session. The compaction's write therefore lands while the stream is open.
  *
  * ==Honesty about what this proves==
  * The window is wide (seconds) but it is still a *timing* construction, not a deterministic interleaving:
  * the SDK gives no hook to hold a compaction at a chosen instant. So a green run is evidence, not proof.
  * What it does rule out is the failure that would matter — a streamed turn erroring, truncating, or
  * losing its reply because its history was replaced underneath it.
  */
@Tag("slow")
class StreamingRaceIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val streaming = new TestModelProvider()
  private val summariser = new TestModelProvider()

  private val Answer = "Work survives a restart because the runtime persists it as the loop progresses."

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withAdditionalConfig("compaction.max-bytes = 4096")
      .withModelProvider(classOf[StreamingChatAgent], streaming)
      .withModelProvider(classOf[CompactionAgent], summariser)

  private def stream(sessionId: String, message: String) =
    httpClient
      .POST(s"/stream-chat/$sessionId")
      .withRequestBody(StreamingRaceIntegrationTest.Body(message))
      .invoke()

  @Test
  def aStreamedTurnSurvivesItsHistoryBeingReplacedUnderneathIt(): Unit =
    summariser.fixedResponse { (_: InputMessage) =>
      Thread.sleep(3000)
      AiResponse("""{"userMessage":"The user asked about durability.","aiMessage":"The assistant explained it."}""")
    }
    val padding = "x" * 700
    streaming.fixedResponse(s"$Answer $padding")

    val session = "us3-streaming-race"
    // Drive streamed turns until one crosses the 4 KiB threshold and starts a compaction.
    for i <- 1 to 3 do
      val response = stream(session, s"q$i $padding")
      assertThat(response.status.intValue).isEqualTo(200)

    // The compaction is now inside the slow summariser. Start another streamed turn into the same
    // history, so the replacement lands while this stream is open.
    Thread.sleep(800)
    streaming.fixedResponse(Answer)
    val duringCompaction = stream(session, "does this survive?")

    logger.info(
      "R-5 >>> streamed turn during a compaction: status={} bytes={}",
      duringCompaction.status.intValue,
      duringCompaction.body.utf8String.length)

    // The finding: a streamed turn is not broken by a compaction landing mid-assembly.
    assertThat(duringCompaction.status.intValue).isEqualTo(200)
    assertThat(duringCompaction.body.utf8String).isEqualTo(Answer)

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => httpClient.GET(s"/compaction/$session").invoke().status.intValue == 200)

    val record = httpClient.GET(s"/compaction/$session").invoke().body.utf8String
    val history = new SessionMemoryGateway(componentClient).history(session).messages.asScala.toList
    logger.info("R-5 >>> record: {}", record)
    logger.info("R-5 >>> history after the race: {} messages", history.size)

    // And the streamed turn is still IN the history — the merge (R-4 as corrected) keeps what arrived
    // while the summariser worked, streamed or not.
    val texts = history.map(_.toString).mkString
    assertThat(texts).contains("does this survive?")

  /** The plainer half of the same claim: capability 14's surface keeps working once the session HAS been
    * compacted. A stream reading a two-message summarised history must behave like any other. */
  @Test
  def aStreamedTurnWorksNormallyAfterTheSessionHasBeenCompacted(): Unit =
    summariser.fixedResponse(
      """{"userMessage":"The user asked things.","aiMessage":"The assistant answered."}""")
    val padding = "y" * 700
    streaming.fixedResponse(s"$Answer $padding")

    val session = "us3-streaming-after"
    for i <- 1 to 3 do stream(session, s"q$i $padding")

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => httpClient.GET(s"/compaction/$session").invoke().status.intValue == 200)

    streaming.fixedResponse(Answer)
    val afterwards = stream(session, "and now?")
    logger.info("R-5 >>> streamed turn after compaction: status={}", afterwards.status.intValue)
    assertThat(afterwards.status.intValue).isEqualTo(200)
    assertThat(afterwards.body.utf8String).isEqualTo(Answer)

object StreamingRaceIntegrationTest:
  final case class Body(message: String)
