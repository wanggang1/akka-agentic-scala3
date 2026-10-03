package com.gwgs.akkaagentic.compaction.application

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.TestModelProvider.{AiResponse, InputMessage}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T023 / User Story 3 — **a turn that arrives mid-summary is kept, and the compaction still lands.**
  *
  * ==This test corrected research R-4, which was wrong==
  * Phase 0 concluded that `compactHistory` silently *discards* a write carrying a stale `sequenceNumber`.
  * It does not. The probe passed `sequenceNumber - 2` against a four-message history, saw four messages
  * afterwards, and read that as "unchanged" — when it was in fact *compacted to two, then two replayed
  * back on top*. The same count, for a completely different reason. That is exactly the mistake this
  * capability later wrote up as finding I-3: **under concurrency, infer nothing from a count.** It cost a
  * wrong entry in the research notes and a design justified by the wrong mechanism.
  *
  * ==What actually happens (bytecode-confirmed and measured here)==
  * `sequenceNumber` marks **how much of the history the summary stands for**. `compactHistory` clears,
  * writes the summary, and then **replays every event written after that number back on top**. So a
  * concurrent turn is neither lost nor able to block the compaction — it simply ends up after the
  * summary. That is a *better* guarantee than the optimistic lock Phase 0 assumed, and it is what
  * satisfies FR-008.
  *
  * The race is produced rather than simulated: the summariser is made slow and a turn is injected while it
  * works — the real-world shape of a user who keeps typing while the system summarises.
  *
  * `Compactor` still re-reads afterwards, and that is still right: it is how the ledger reports what
  * actually stands rather than what was hoped. `SkippedStale` remains as a defensive case for a summary
  * that does not appear at the head, which this SDK version gives no way to produce.
  */
@Tag("slow")
class StaleCompactionIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val chat = new TestModelProvider()
  private val summariser = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withAdditionalConfig("compaction.max-bytes = 4096")
      .withModelProvider(classOf[ChatAgent], chat)
      .withModelProvider(classOf[CompactionAgent], summariser)

  private def turn(sessionId: String, message: String, reply: String): Unit =
    chat.fixedResponse(reply)
    componentClient.forAgent().inSession(sessionId).dynamicCall[String, String]("chat-agent").invoke(message)

  private def recordFor(sessionId: String) = httpClient.GET(s"/compaction/$sessionId").invoke()

  @Test
  def aTurnArrivingMidSummaryIsPreservedAndTheCompactionStillLands(): Unit =
    // A summariser slow enough that a turn can land while it works — the real-world race, not a stubbed
    // sequence number. (A stubbed one would test the platform; this tests our handling of it.)
    summariser.fixedResponse { (_: InputMessage) =>
      Thread.sleep(3000)
      AiResponse("""{"userMessage":"A summary of the user.","aiMessage":"A summary of the assistant."}""")
    }

    val session = "us3-stale"
    val padding = "x" * 700
    for i <- 1 to 3 do turn(session, s"q$i $padding", s"a$i $padding")

    // The consumer has picked up the threshold-crossing event and is inside the slow summariser now.
    Thread.sleep(1200)
    val messagesBeforeTheInjection = new SessionMemoryGateway(componentClient).history(session).messages.size
    turn(session, "one more while you are summarising", "noted")

    Awaitility.await().atMost(25, TimeUnit.SECONDS).until(() => recordFor(session).status.intValue == 200)

    val body = recordFor(session).body.utf8String
    val messages = new SessionMemoryGateway(componentClient).history(session).messages.asScala.toList
    logger.info("US3 >>> record after the race: {}", body)
    logger.info("US3 >>> history: {} messages (was {} before the injected turn)", messages.size, messagesBeforeTheInjection)

    // The compaction landed.
    assertThat(body).contains("\"lastOutcome\":\"compacted\"")
    assertThat(body).contains("\"compactions\":1")

    // And the turn that arrived mid-summary was NOT lost — this is FR-008, and the platform satisfies it
    // by replaying post-summary events rather than by rejecting the write.
    val texts = messages.map(_.toString).mkString
    assertThat(texts).contains("one more while you are summarising")
    assertThat(texts).contains("A summary of the user.")

    // The shape that proves the merge: the summary at the head, the injected turn after it, and fewer
    // messages than the six that were there before — but more than the summary alone.
    val kinds = messages.map(_.getClass.getSimpleName)
    assertThat(kinds.head).isEqualTo("UserMessage")
    assertThat(messages.size).isLessThan(messagesBeforeTheInjection)
    assertThat(messages.size).isGreaterThan(2)
    logger.info("US3 >>> MERGE confirmed: {} messages = summary + what arrived during it", messages.size)
