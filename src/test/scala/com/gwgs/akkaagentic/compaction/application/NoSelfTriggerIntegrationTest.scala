package com.gwgs.akkaagentic.compaction.application

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.TestModelProvider.{AiResponse, InputMessage}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T024 / User Story 3 — **compaction does not trigger itself**, pinned rather than assumed.
  *
  * `compactHistory` persists three events, and the last of them is an `AiMessageAdded` — the very event
  * this capability's trigger listens to (research S-6). So the shape of an infinite loop is present: write
  * a summary, see your own write, summarise again. Research R-2 measured why it does not happen: the size
  * reported on that event is computed **after** `HistoryCleared`, so it describes the summary (22 bytes
  * when measured) and not the history that was replaced.
  *
  * That is the SDK's ordering, not a property of our code — which is exactly why it is pinned here. If a
  * future SDK computed the size before the clear, this test fails loudly instead of the service quietly
  * spending a model call per compaction for ever.
  *
  * The assertion is the **number of summariser invocations**, because that is what a loop would cost.
  */
@Tag("slow")
class NoSelfTriggerIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val chat = new TestModelProvider()
  private val summariser = new TestModelProvider()
  private val summariserCalls = new AtomicInteger(0)

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withAdditionalConfig("compaction.max-bytes = 4096")
      .withModelProvider(classOf[ChatAgent], chat)
      .withModelProvider(classOf[CompactionAgent], summariser)

  private def turn(sessionId: String, message: String, reply: String): Unit =
    chat.fixedResponse(reply)
    componentClient.forAgent().inSession(sessionId).dynamicCall[String, String]("chat-agent").invoke(message)

  @Test
  def compactionDoesNotSeeItsOwnWriteAndSummariseAgain(): Unit =
    summariser.fixedResponse { (_: InputMessage) =>
      summariserCalls.incrementAndGet()
      AiResponse("""{"userMessage":"A summary of the user.","aiMessage":"A summary of the assistant."}""")
    }

    val session = "us3-no-loop"
    val padding = "x" * 700
    for i <- 1 to 3 do turn(session, s"q$i $padding", s"a$i $padding")

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => httpClient.GET(s"/compaction/$session").invoke().status.intValue == 200)

    // Long enough that a loop would have run away: each cycle is one entity write and one model call.
    Thread.sleep(5000)

    val calls = summariserCalls.get()
    val body = httpClient.GET(s"/compaction/$session").invoke().body.utf8String
    val history = new SessionMemoryGateway(componentClient).history(session)
    logger.info("US3 >>> summariser invocations after compaction + 5 s: {}", calls)
    logger.info("US3 >>> record: {}", body)
    logger.info(
      "US3 >>> the compacted history is {} bytes — this is R-2's mechanism: well under the 4096 threshold",
      Compactor.sizeOf(history))

    assertThat(calls).isEqualTo(1)
    assertThat(body).contains("\"compactions\":1")
    // The mechanism itself: what the trigger now sees for this session is far below the threshold, so the
    // decision is `Leave`. If this ever exceeded the threshold, the loop above would be live.
    assertThat(Compactor.sizeOf(history)).isLessThan(4096L)
