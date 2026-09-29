package com.gwgs.akkaagentic.compaction.api

import java.util.concurrent.TimeUnit

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import com.gwgs.akkaagentic.compaction.application.CompactionAgent
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T027 / User Story 4 — the threshold is configuration, proven by **configuration**.
  *
  * This class and [[HigherThresholdConfigIntegrationTest]] run the **same drive against the same
  * production code** and differ in exactly one key: `compaction.max-bytes`. Here it is low enough that the
  * drive crosses it; there it is not. That is capability 12's `report-only` shape, and it is the only way
  * to demonstrate a configuration switch without asserting on the configuration itself — which would prove
  * only that the test can read its own fixture.
  */
@Tag("slow")
class ThresholdConfigIntegrationTest extends TestKitSupport:

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
  def atFourKibTheSameDriveCompacts(): Unit =
    summariser.fixedResponse("""{"userMessage":"A summary.","aiMessage":"Another summary."}""")
    val session = "us4-low-threshold"
    ThresholdConfigIntegrationTest.drive(componentClient, chat, session)

    Awaitility
      .await()
      .atMost(25, TimeUnit.SECONDS)
      .until(() => httpClient.GET(s"/compaction/$session").invoke().status.intValue == 200)

    val body = httpClient.GET(s"/compaction/$session").invoke().body.utf8String
    logger.info("US4 >>> at 4 KiB: {}", body)
    assertThat(body).contains("\"lastOutcome\":\"compacted\"")

object ThresholdConfigIntegrationTest:
  /** The identical drive both classes use — roughly 4.2 KiB of history. */
  def drive(
      componentClient: akka.javasdk.client.ComponentClient,
      chat: TestModelProvider,
      session: String): Unit =
    val padding = "x" * 700
    for i <- 1 to 3 do
      chat.fixedResponse(s"a$i $padding")
      componentClient.forAgent().inSession(session).dynamicCall[String, String]("chat-agent").invoke(s"q$i $padding")
