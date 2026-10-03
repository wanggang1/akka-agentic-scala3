package com.gwgs.akkaagentic.compaction.api

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import com.gwgs.akkaagentic.compaction.application.{CompactionAgent, SessionMemoryGateway}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T028 / User Story 4 — **off means off**, and off means capability 6's behaviour exactly.
  *
  * The switch exists for an operator who wants to reproduce a problem, or who would rather pay for full
  * history than for summaries. So "disabled" has to mean the service behaves as it did before this
  * capability existed: history grows, nothing is summarised, and no model is called.
  */
@Tag("slow")
class CompactionDisabledIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val chat = new TestModelProvider()
  private val summariser = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      // A threshold a single turn would cross, so only `enabled = false` can explain the result.
      .withAdditionalConfig("compaction.max-bytes = 1024")
      .withAdditionalConfig("compaction.enabled = false")
      .withModelProvider(classOf[ChatAgent], chat)
      .withModelProvider(classOf[CompactionAgent], summariser)

  @Test
  def aSessionWellPastAnyThresholdIsNeverCompacted(): Unit =
    // Deliberately unscripted: if the summariser is called at all, the turn fails and this test says so.
    val session = "us4-disabled"
    val padding = "x" * 700
    for i <- 1 to 5 do
      chat.fixedResponse(s"a$i $padding")
      componentClient.forAgent().inSession(session).dynamicCall[String, String]("chat-agent").invoke(s"q$i $padding")

    Thread.sleep(3000)

    val history = new SessionMemoryGateway(componentClient).history(session).messages.asScala.toList
    val status = httpClient.GET(s"/compaction/$session").invoke().status.intValue
    logger.info("US4 >>> disabled: {} messages retained, GET -> {}", history.size, status)

    // Capability 6's behaviour: every turn still there, nothing summarised, nothing recorded.
    assertThat(history.size).isEqualTo(10)
    assertThat(status).isEqualTo(404)
