package com.gwgs.akkaagentic.compaction.probe

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.javasdk.agent.{SessionMemoryEntity, SessionMessage}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** Phase 0 probe for feature 019 — the runtime half. NOT production; deleted or trimmed before merge.
  *
  * Answers R-1 (can a Scala consumer match the runtime-owned session memory events), R-2 (what
  * `historySizeInBytes` reads as on the `AiMessageAdded` that compaction ITSELF emits — the self-trigger
  * loop hazard), R-3 (one Java class holding both method references), and R-4 (does the sequenceNumber
  * guard reject a stale write, and what does the caller see).
  *
  * Sessions are driven through capability 4's `ChatAgent`, which is the least-interop agent in the project
  * (bare `String` in and out, no tools), so what is measured is the memory mechanism and not an agent.
  */
@Tag("slow")
class CompactionProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[ChatAgent], model)

  private def gateway = new SessionMemoryProbeGateway(componentClient)

  private def turn(sessionId: String, message: String, reply: String): String =
    model.fixedResponse(reply)
    componentClient.forAgent().inSession(sessionId).dynamicCall[String, String]("chat-agent").invoke(message)

  private def awaitSeen(sessionId: String, atLeast: Int): List[String] =
    Awaitility
      .await()
      .atMost(15, TimeUnit.SECONDS)
      .until(() => SessionMemoryProbeConsumer.seen(sessionId).size >= atLeast)
    SessionMemoryProbeConsumer.seen(sessionId)

  /** R-1: a Scala consumer over the RUNTIME-OWNED SessionMemoryEntity, matching its Event records. */
  @Test
  def r1_aScalaConsumerReceivesAndMatchesSessionMemoryEvents(): Unit =
    val session = "probe-r1"
    turn(session, "my name is Ada", "Nice to meet you, Ada!")
    turn(session, "what is my name?", "Your name is Ada.")

    val seen = awaitSeen(session, 4)
    logger.info("R-1 >>> events seen for [{}]: {}", session, seen.mkString(" | "))
    assertThat(seen.mkString(" | ")).contains("UserMessageAdded")
    assertThat(seen.mkString(" | ")).contains("AiMessageAdded")
    assertThat(seen.count(_.startsWith("UNMATCHED"))).isEqualTo(0)

  /** R-2 + R-3: compaction through ONE Java class, and what size its own AiMessageAdded reports. */
  @Test
  def r2_whatCompactionsOwnEventReportsAsHistorySize(): Unit =
    val session = "probe-r2"
    val padding = "x" * 2000
    for i <- 1 to 4 do turn(session, s"turn $i $padding", s"reply $i $padding")
    awaitSeen(session, 8)

    val before = gateway.history(session)
    val sizesBefore = SessionMemoryProbeConsumer.historySizes(session)
    logger.info(
      "R-2 >>> before: messages={} seqNr={} historySizes={}",
      before.messages.size,
      before.sequenceNumber,
      sizesBefore.mkString(","))

    gateway.compact(session, "chat-agent", "SUMMARY user", "SUMMARY ai", before.sequenceNumber)

    Awaitility
      .await()
      .atMost(15, TimeUnit.SECONDS)
      .until(() => SessionMemoryProbeConsumer.clearedCount(session) >= 1)

    val after = gateway.history(session)
    val sizesAfter = SessionMemoryProbeConsumer.historySizes(session)
    logger.info(
      "R-2 >>> after: messages={} seqNr={} texts={} historySizes={}",
      after.messages.size,
      after.sequenceNumber,
      after.messages.asScala.map(_.getClass.getSimpleName).mkString(","),
      sizesAfter.mkString(","))
    logger.info(
      "R-2 >>> LOOP HAZARD: history size reported on compaction's OWN AiMessageAdded = {} (was {})",
      sizesAfter.lastOption.getOrElse(-1L),
      sizesBefore.lastOption.getOrElse(-1L))

    // The summary replaced the turns.
    assertThat(after.messages.size).isEqualTo(2)
    assertThat(after.messages.asScala.map(_.getClass.getSimpleName).mkString(",")).isEqualTo("UserMessage,AiMessage")
    // And it is smaller than what it replaced.
    assertThat(sizesAfter.last < sizesBefore.last).isTrue()

  /** R-4: the concurrency guard — a stale sequence number. */
  @Test
  def r4_whatAStaleSequenceNumberDoes(): Unit =
    val session = "probe-r4"
    turn(session, "first", "one")
    turn(session, "second", "two")
    awaitSeen(session, 4)

    val stale = gateway.history(session).sequenceNumber - 2
    val outcome =
      try
        gateway.compact(session, "chat-agent", "STALE user", "STALE ai", stale)
        "ACCEPTED (no error)"
      catch case t: Throwable => s"REJECTED: ${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"
    logger.info("R-4 >>> stale sequenceNumber [{}] -> {}", stale, outcome)

    val after = gateway.history(session)
    logger.info("R-4 >>> history after the stale write: messages={}", after.messages.size)
    // Recorded rather than asserted: this probe exists to find out which it is.
    assertThat(outcome).isNotBlank()

  /** R-6: the turn-alignment invariant of the SDK's OWN byte bound, observed rather than read. */
  @Test
  def r6_theSdkOwnByteBoundKeepsHistoryStartingAtAUserMessage(): Unit =
    val session = "probe-r6"
    val padding = "y" * 4000
    for i <- 1 to 12 do turn(session, s"q$i $padding", s"a$i $padding")
    awaitSeen(session, 24)

    val history = gateway.history(session)
    val kinds = history.messages.asScala.map(_.getClass.getSimpleName).toList
    logger.info("R-6 >>> after 12 padded turns: {} messages retained, first={} ", kinds.size, kinds.headOption)
    logger.info("R-6 >>> retained sequence: {}", kinds.mkString(","))
    // 24 messages were written; if fewer are retained, eviction happened.
    logger.info("R-6 >>> eviction occurred = {}", kinds.size < 24)
    // The invariant research S-2 read out of the bytecode: the window begins at a user turn.
    assertThat(kinds.head).isEqualTo("UserMessage")
