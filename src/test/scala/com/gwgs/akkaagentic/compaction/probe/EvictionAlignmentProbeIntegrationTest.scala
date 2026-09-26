package com.gwgs.akkaagentic.compaction.probe

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** Phase 0 probe for feature 019, R-6. NOT production.
  *
  * Research S-2 read out of the bytecode that the SDK's OWN byte bound
  * (`akka.javasdk.agent.memory.limited-window.max-size`, default and maximum 510 KiB) evicts oldest-first
  * and **then** sweeps head messages until the head is a `UserMessage` — so the retained window always
  * begins at a turn boundary and a tool-call pair can never be split. That is the finding the whole
  * capability's motivation now rests on, so it is worth observing rather than only reading.
  *
  * 510 KiB is impractical to reach in a test, so the bound is overridden to **8 KiB** here. The mechanism
  * is the same; only the threshold moves.
  */
@Tag("slow")
class EvictionAlignmentProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withAdditionalConfig("akka.javasdk.agent.memory.limited-window.max-size = 8 KiB")
      .withModelProvider(classOf[ChatAgent], model)

  @Test
  def r6_evictionKeepsTheWindowStartingAtAUserMessage(): Unit =
    val session = "probe-r6-small"
    val padding = "z" * 900
    val turns = 12
    for i <- 1 to turns do
      model.fixedResponse(s"a$i $padding")
      componentClient
        .forAgent()
        .inSession(session)
        .dynamicCall[String, String]("chat-agent")
        .invoke(s"q$i $padding")

    Awaitility
      .await()
      .atMost(20, TimeUnit.SECONDS)
      .until(() => SessionMemoryProbeConsumer.seen(session).size >= turns * 2)

    val history = new SessionMemoryProbeGateway(componentClient).history(session)
    val kinds = history.messages.asScala.map(_.getClass.getSimpleName).toList
    val texts = history.messages.asScala.map {
      case u: akka.javasdk.agent.SessionMessage.UserMessage => u.text.take(4)
      case a: akka.javasdk.agent.SessionMessage.AiMessage   => a.text.take(4)
      case other                                            => other.getClass.getSimpleName
    }.toList

    logger.info("R-6 >>> wrote {} messages under an 8 KiB bound; {} retained", turns * 2, kinds.size)
    logger.info("R-6 >>> EVICTION OCCURRED = {}", kinds.size < turns * 2)
    logger.info("R-6 >>> retained heads: {}", texts.take(6).mkString(","))
    logger.info("R-6 >>> first retained message is a {}", kinds.headOption.getOrElse("<none>"))

    // Eviction must actually have happened, or the probe proves nothing.
    assertThat(kinds.size).isLessThan(turns * 2)
    // S-2's invariant, observed: the window begins at a user turn, never mid-turn.
    assertThat(kinds.head).isEqualTo("UserMessage")
    // And it alternates cleanly from there — no half turn at the head.
    assertThat(kinds.take(2).mkString(",")).isEqualTo("UserMessage,AiMessage")
