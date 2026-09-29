package com.gwgs.akkaagentic.compaction.api

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.javasdk.agent.SessionMessage
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import com.gwgs.akkaagentic.compaction.application.{CompactionAgent, SessionMemoryGateway}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T025 / User Story 3 — a compacted history contains **no tool-call pair at all**, so none can be split.
  *
  * Worth being precise about what this does and does not defend against. It is **not** protection from the
  * SDK: research S-2 measured that the platform's own byte-bound eviction sweeps until the head of the
  * history is a `UserMessage`, so it never orphans a pair either. The mechanism that broke capability 6
  * was `readLast(N)`'s `subList`, which this capability does not use and a test elsewhere forbids.
  *
  * This is a property of **our** summary, and it holds by construction: a summary is two prose messages.
  * It is pinned because "by construction" is only true while the construction stays that way — a future
  * change that carried structured tool calls into the summary would reintroduce exactly the shape
  * capability 6 was burned by, and this test is what would say so.
  */
@Tag("slow")
class NoOrphanedPairIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val chat = new TestModelProvider()
  private val summariser = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withAdditionalConfig("compaction.max-bytes = 4096")
      .withModelProvider(classOf[ChatAgent], chat)
      .withModelProvider(classOf[CompactionAgent], summariser)

  @Test
  def aCompactedHistoryIsTwoProseMessagesAndNothingElse(): Unit =
    summariser.fixedResponse(
      """{"userMessage":"The user asked several things.","aiMessage":"The assistant answered them."}""")

    val session = "us3-no-orphan"
    val padding = "x" * 700
    for i <- 1 to 3 do
      chat.fixedResponse(s"a$i $padding")
      componentClient
        .forAgent()
        .inSession(session)
        .dynamicCall[String, String]("chat-agent")
        .invoke(s"q$i $padding")

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => httpClient.GET(s"/compaction/$session").invoke().status.intValue == 200)

    val messages = new SessionMemoryGateway(componentClient).history(session).messages.asScala.toList
    val kinds = messages.map(_.getClass.getSimpleName)
    logger.info("US3 >>> compacted history: {}", kinds.mkString(","))

    // Exactly a user turn and an assistant turn.
    assertThat(kinds.asJava).containsExactly("UserMessage", "AiMessage")

    // No tool traffic survives in any form: no response message, and no request hanging off the AI turn.
    assertThat(messages.exists(_.isInstanceOf[SessionMessage.ToolCallResponse])).isFalse()
    val danglingRequests = messages.collect { case ai: SessionMessage.AiMessage => ai.toolCallRequests.size }
    assertThat(danglingRequests.sum).isEqualTo(0)

    // And the invariant the SDK's own eviction also maintains: the window begins at a user turn.
    assertThat(kinds.head).isEqualTo("UserMessage")
