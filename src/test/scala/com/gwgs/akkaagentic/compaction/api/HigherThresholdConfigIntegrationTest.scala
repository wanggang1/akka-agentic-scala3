package com.gwgs.akkaagentic.compaction.api

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import com.gwgs.akkaagentic.compaction.application.CompactionAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T027's other half — the **same drive, the same code, one different key**.
  *
  * `compaction.max-bytes` is 32 KiB here, so the ~4.2 KiB of history
  * [[ThresholdConfigIntegrationTest]] compacts is left alone. Two classes differing in one line is what
  * makes this a demonstration of the switch rather than of the fixture.
  */
@Tag("slow")
class HigherThresholdConfigIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val chat = new TestModelProvider()
  private val summariser = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withAdditionalConfig("compaction.max-bytes = 32768")
      .withModelProvider(classOf[ChatAgent], chat)
      .withModelProvider(classOf[CompactionAgent], summariser)

  @Test
  def atThirtyTwoKibTheSameDriveIsLeftAlone(): Unit =
    // Any summariser call would be a bug; leaving it unscripted makes one fail loudly.
    val session = "us4-high-threshold"
    ThresholdConfigIntegrationTest.drive(componentClient, chat, session)

    Thread.sleep(3000) // long enough for a compaction to have happened if the threshold were crossed

    val status = httpClient.GET(s"/compaction/$session").invoke().status.intValue
    logger.info("US4 >>> at 32 KiB the same drive left {} uncompacted (GET -> {})", session, status)
    assertThat(status).isEqualTo(404)
