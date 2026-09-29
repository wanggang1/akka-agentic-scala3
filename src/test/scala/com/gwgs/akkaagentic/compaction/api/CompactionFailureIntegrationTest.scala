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

/** T022 / User Story 3 — compaction must be invisible when it works and **harmless when it fails**.
  *
  * Compaction runs because a history got large, which is a condition of success rather than of user
  * intent. So a user mid-conversation must never see an error because the system was busy summarising,
  * and a failed attempt must leave the history exactly as it was — not partially replaced, not emptied.
  *
  * Two ways to fail are covered, and they are different: the model **erroring**, and the model
  * **succeeding with nothing useful**. The second is the one that would slip through a naive
  * implementation, because it arrives on the success path: replacing a conversation with an empty summary
  * would satisfy the byte bound perfectly and destroy the point.
  */
@Tag("slow")
class CompactionFailureIntegrationTest extends TestKitSupport:

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

  private def turn(sessionId: String, message: String, reply: String): String =
    chat.fixedResponse(reply)
    componentClient.forAgent().inSession(sessionId).dynamicCall[String, String]("chat-agent").invoke(message)

  private def textsOf(sessionId: String): List[String] =
    gateway.history(sessionId).messages.asScala.toList.map {
      case u: akka.javasdk.agent.SessionMessage.UserMessage => u.text
      case a: akka.javasdk.agent.SessionMessage.AiMessage   => a.text
      case other                                            => other.toString
    }

  private def driveOverTheThreshold(sessionId: String, marker: String): List[String] =
    val padding = marker * 700
    for i <- 1 to 3 do turn(sessionId, s"q$i $padding", s"a$i $padding")
    textsOf(sessionId)

  private def recordFor(sessionId: String) = httpClient.GET(s"/compaction/$sessionId").invoke()

  /** The model errors. The turn already returned; the history must be untouched and the failure recorded. */
  @Test
  def aSummariserThatErrorsLeavesTheHistoryExactlyAsItWas(): Unit =
    summariser.whenMessage((_: String) => true).failWith(new RuntimeException("simulated summariser failure"))
    val session = "us3-model-errors"

    val before = driveOverTheThreshold(session, "x")
    Awaitility.await().atMost(25, TimeUnit.SECONDS).until(() => recordFor(session).status.intValue == 200)

    val after = textsOf(session)
    logger.info("US3 >>> error path: {} messages before, {} after", before.size, after.size)
    // Byte-for-byte: not truncated, not emptied, not partially replaced.
    assertThat(after.asJava).isEqualTo(before.asJava)
    assertThat(recordFor(session).body.utf8String).contains("\"lastOutcome\":\"failed\"")
    assertThat(recordFor(session).body.utf8String).contains("\"compactions\":0")

  /** The model succeeds and returns nothing usable. This arrives on the SUCCESS path, so it is the one a
    * naive implementation writes straight into the session — satisfying the byte bound by deleting the
    * conversation. */
  @Test
  def aBlankSummaryIsTreatedAsFailureNotAsAVerySmallSummary(): Unit =
    summariser.fixedResponse("""{"userMessage":"","aiMessage":"   "}""")
    val session = "us3-blank-summary"

    val before = driveOverTheThreshold(session, "y")
    Awaitility.await().atMost(25, TimeUnit.SECONDS).until(() => recordFor(session).status.intValue == 200)

    val after = textsOf(session)
    logger.info("US3 >>> blank path: {} messages before, {} after", before.size, after.size)
    assertThat(after.asJava).isEqualTo(before.asJava)
    assertThat(recordFor(session).body.utf8String).contains("\"lastOutcome\":\"failed\"")

  /** FR-005's other half: whatever compaction does, the conversation keeps working. */
  @Test
  def theConversationStillWorksAfterAFailedCompaction(): Unit =
    summariser.whenMessage((_: String) => true).failWith(new RuntimeException("simulated summariser failure"))
    val session = "us3-still-works"

    driveOverTheThreshold(session, "z")
    Awaitility.await().atMost(25, TimeUnit.SECONDS).until(() => recordFor(session).status.intValue == 200)

    assertThat(turn(session, "are you still there?", "Still here.")).isEqualTo("Still here.")
