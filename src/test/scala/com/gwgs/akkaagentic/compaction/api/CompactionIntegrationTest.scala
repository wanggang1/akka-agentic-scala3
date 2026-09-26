package com.gwgs.akkaagentic.compaction.api

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import com.gwgs.akkaagentic.compaction.application.{CompactionAgent, SessionMemoryGateway}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T018 / User Story 1 — the bound itself, proven offline.
  *
  * Sessions are driven through capability 4's `ChatAgent`, the least-interop agent in the project (bare
  * `String` in and out), so what is under test is the compaction mechanism and not an agent. The
  * summariser is mocked; its *wording* is never asserted, only that a usable summary replaces the history.
  *
  * The threshold is lowered to 4 KiB here so a handful of turns crosses it. At the shipped 128 KiB default
  * this test would take a very long time to say the same thing.
  */
@Tag("slow")
class CompactionIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val chat = new TestModelProvider()
  private val summariser = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withAdditionalConfig("compaction.max-bytes = 4096")
      .withModelProvider(classOf[ChatAgent], chat)
      .withModelProvider(classOf[CompactionAgent], summariser)

  private def gateway = new SessionMemoryGateway(componentClient)

  private def summaryIsScripted(): Unit =
    summariser.fixedResponse(
      """{"userMessage":"The user asked several questions about akka.",
        |"aiMessage":"The assistant answered them, establishing that sessions are compacted by size."}"""
        .stripMargin.replace("\n", ""))

  private def turn(sessionId: String, message: String, reply: String): String =
    chat.fixedResponse(reply)
    componentClient.forAgent().inSession(sessionId).dynamicCall[String, String]("chat-agent").invoke(message)

  private def compactionRecord(sessionId: String) =
    httpClient.GET(s"/compaction/$sessionId").invoke()

  /** SC-001: past the threshold, the stored history is smaller and the next turn still answers. */
  @Test
  def aSessionPastTheThresholdIsSummarisedAndKeepsWorking(): Unit =
    summaryIsScripted()
    val session = "us1-compacts"
    val padding = "x" * 700
    // Exactly enough turns to cross 4 KiB (~1.4 KiB each) and then stop. Driving more turns is what a
    // real conversation does, but it makes the assertion below timing-dependent: turns that land while a
    // compaction is in flight are appended after the summary, so the history is the summary PLUS them.
    // That is correct behaviour, and it is asserted separately in `theSummaryLandsAtTheHead` below.
    for i <- 1 to 3 do turn(session, s"q$i $padding", s"a$i $padding")

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => compactionRecord(session).status.intValue == 200)

    val after = gateway.history(session)
    val kinds = after.messages.asScala.map(_.getClass.getSimpleName).toList
    logger.info("US1 >>> after compaction: {} messages, {}", kinds.size, kinds.mkString(","))

    // The summary replaced the turns.
    assertThat(kinds.mkString(",")).isEqualTo("UserMessage,AiMessage")
    // And it is genuinely smaller than what crossed the threshold.
    val body = compactionRecord(session).body.utf8String
    logger.info("US1 >>> GET /compaction/{} -> {}", session, body)
    // Exactly one compaction, however many events crossed the threshold: a burst of turns produces a
    // burst of events carrying stale sizes, and only the attempt that finds the history still over the
    // threshold does any work (measured while implementing this story).
    assertThat(body).contains("\"compactions\":1")
    assertThat(body).contains("\"lastOutcome\":\"compacted\"")
    assertThat(body).contains("\"lastMessagesReplaced\":6")

    // The conversation still works afterwards — the point of a bound is that it does not end the session.
    val reply = turn(session, "still there?", "Still here.")
    assertThat(reply).isEqualTo("Still here.")

  /** The concurrency-tolerant form of the same guarantee: whatever else has been appended, the summary is
    * at the head. This is also the discriminator `Compactor` uses to tell a real compaction from a write
    * the platform silently discarded (research R-4), so it is worth asserting directly. */
  @Test
  def theSummaryLandsAtTheHeadEvenWhileTheConversationContinues(): Unit =
    summaryIsScripted()
    val session = "us1-concurrent"
    val padding = "y" * 700
    for i <- 1 to 6 do turn(session, s"q$i $padding", s"a$i $padding")

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => compactionRecord(session).status.intValue == 200)

    val messages = gateway.history(session).messages.asScala.toList
    logger.info("US1 >>> {} messages retained, head text starts: {}", messages.size, messages.head)
    assertThat(messages.head.componentId).isEqualTo("compaction-agent")
    // NOT a count assertion: six turns cross a 4 KiB threshold twice, so two compactions here are
    // correct, and the number depends on how the turns interleave with the summariser. What is invariant
    // is that the head is ours and the last attempt succeeded.
    assertThat(compactionRecord(session).body.utf8String).contains("\"lastOutcome\":\"compacted\"")

  /** The common case must cost nothing: below the threshold, no summariser call and no record. */
  @Test
  def aSessionBelowTheThresholdIsLeftAlone(): Unit =
    summariser.reset() // any call to it would now fail the turn, which is the assertion
    val session = "us1-untouched"
    turn(session, "short question", "short answer")
    turn(session, "another", "and another")

    Thread.sleep(2000) // long enough for a trigger to have fired if it were going to

    val history = gateway.history(session)
    assertThat(history.messages.size).isEqualTo(4)
    assertThat(compactionRecord(session).status.intValue).isEqualTo(404)

  /** The collection surface: an empty answer is a success, and a blank id is the caller's fault. */
  @Test
  def theReadSurfaceAnswersAllThreeWays(): Unit =
    val all = httpClient.GET("/compaction").invoke()
    assertThat(all.status.intValue).isEqualTo(200)
    assertThat(all.body.utf8String).contains("\"since\"")
    assertThat(httpClient.GET("/compaction/never-seen-at-all").invoke().status.intValue).isEqualTo(404)
    assertThat(httpClient.GET("/compaction/%20%20").invoke().status.intValue).isEqualTo(400)
