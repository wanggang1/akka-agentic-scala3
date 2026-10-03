package com.gwgs.akkaagentic.compaction.api

import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.TestModelProvider.{AiResponse, InputMessage}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import com.gwgs.akkaagentic.compaction.application.{CompactionAgent, SessionMemoryGateway}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** T019 / User Story 2 — what survives being compacted.
  *
  * ==What this test can honestly prove, and what it deliberately does not==
  * The summariser is mocked, so asserting that "the summary mentions Ada" would assert nothing but the
  * fixture this test itself wrote. Two claims are real offline, and they are the two halves of the
  * pipeline either side of the model:
  *
  *  1. **Nothing is lost on the way IN.** The conversation handed to the summariser is captured with
  *     `withMessageSelector` and asserted to still contain what was established several turns earlier.
  *     This is where a real bug would live — a rendering that dropped turns, or dropped the substance of
  *     a tool call (covered over real SDK message types in `SummaryRequestToolProseTest`).
  *  2. **Nothing is lost on the way OUT.** What the summariser returned is exactly what the stored
  *     history now holds, so a good summary cannot be mangled between the model and the session.
  *
  * **What is NOT provable here: recall.** Whether the *model* then uses a summary on a later turn cannot
  * be shown offline — capabilities 4 and 6 both measured that a mocked model receives only the current
  * turn's message, never replayed history (`ChatAgentIntegrationTest.mockReceivesOnlyTheCurrentTurnNot
  * ReplayedHistory` pins that). So US2's third acceptance scenario is a **live** criterion, verified in
  * the quickstart walk and nowhere else. It is labelled rather than simulated, because a simulated
  * version would pass whether or not the feature worked.
  */
class SummaryContentIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val chat = new TestModelProvider()
  private val summariser = new TestModelProvider()

  private val SummaryUser = "The user introduced themselves as Ada and asked about akka."
  private val SummaryAi = "The assistant greeted Ada by name and answered the akka questions."

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withAdditionalConfig("compaction.max-bytes = 4096")
      .withModelProvider(classOf[ChatAgent], chat)
      .withModelProvider(classOf[CompactionAgent], summariser)

  private def gateway = new SessionMemoryGateway(componentClient)

  private def turn(sessionId: String, message: String, reply: String): Unit =
    chat.fixedResponse(reply)
    componentClient.forAgent().inSession(sessionId).dynamicCall[String, String]("chat-agent").invoke(message)

  /** Captures what the summariser was handed, and answers with a fixed summary. */
  private def captureWhatTheSummariserSees(): ConcurrentLinkedQueue[String] =
    val seen = new ConcurrentLinkedQueue[String]()
    summariser.withMessageSelector { (msgs: java.util.List[InputMessage]) =>
      seen.add(msgs.asScala.map(_.content).mkString("\n"))
      msgs.get(msgs.size - 1)
    }
    summariser.fixedResponse((_: InputMessage) =>
      AiResponse(s"""{"userMessage":"$SummaryUser","aiMessage":"$SummaryAi"}"""))
    seen

  /** SC-002, the offline half: a fact established several turns back still reaches the summariser. */
  @Test
  def whatWasEstablishedEarlyStillReachesTheSummariser(): Unit =
    val seen = captureWhatTheSummariserSees()
    val session = "us2-establishes"
    val padding = "x" * 700

    // The fact is established in a SHORT first turn, then buried under padded ones until the 4 KiB
    // threshold is crossed. Three padded turns is not enough once turn 1 is small — the first run of this
    // test timed out having never reached its own trigger, which is worth remembering: a test that does
    // not reach the behaviour it names proves nothing, and a green one would have looked identical.
    turn(session, "my name is Ada", "Nice to meet you, Ada!")
    for i <- 1 to 4 do turn(session, s"q$i about akka $padding", s"a$i akka is a toolkit $padding")

    Awaitility.await().atMost(25, TimeUnit.SECONDS).until(() => !seen.isEmpty)

    val handedOver = seen.peek()
    logger.info("US2 >>> the summariser was handed {} characters", handedOver.length)
    // The fact from turn 1 survived three turns of padding and the rendering.
    assertThat(handedOver).contains("my name is Ada")
    assertThat(handedOver).contains("Nice to meet you, Ada!")
    // And it arrived as a conversation, not as a dump of SDK types.
    assertThat(handedOver).contains("USER:").contains("AI:")
    assertThat(handedOver).doesNotContain("UserMessage[")

  /** The other half: what the summariser returned is what the session now holds. */
  @Test
  def whatTheSummariserReturnedIsExactlyWhatIsStored(): Unit =
    captureWhatTheSummariserSees()
    val session = "us2-roundtrip"
    val padding = "y" * 700
    for i <- 1 to 3 do turn(session, s"q$i $padding", s"a$i $padding")

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => httpClient.GET(s"/compaction/$session").invoke().status.intValue == 200)

    val stored = gateway.history(session).messages.asScala.toList
    val texts = stored.map {
      case u: akka.javasdk.agent.SessionMessage.UserMessage => u.text
      case a: akka.javasdk.agent.SessionMessage.AiMessage   => a.text
      case other                                            => other.toString
    }
    logger.info("US2 >>> stored after compaction: {}", texts.mkString(" | "))
    // `.asJava`: AssertJ on a Scala List gives an ObjectAssert with no containsExactly.
    assertThat(texts.asJava).containsExactly(SummaryUser, SummaryAi)

  /** The summary replaces the turns — this is the loss the contract is explicit about, and it is worth a
    * test so nobody later assumes compaction archives anything. */
  @Test
  def theOriginalTurnsAreGoneNotArchived(): Unit =
    captureWhatTheSummariserSees()
    val session = "us2-replaces"
    val padding = "z" * 700
    for i <- 1 to 3 do turn(session, s"secret$i $padding", s"reply$i $padding")

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => httpClient.GET(s"/compaction/$session").invoke().status.intValue == 200)

    val stored = gateway.history(session).messages.asScala.map(_.toString).mkString
    assertThat(stored).doesNotContain("secret1")
    assertThat(stored).doesNotContain("secret2")
