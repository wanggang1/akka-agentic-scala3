package com.gwgs.akkaagentic.compaction.api

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import com.gwgs.akkaagentic.compaction.application.{CompactionAgent, SessionMemoryGateway}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{Tag, Test}
import org.slf4j.LoggerFactory

/** T029 / User Story 4 — what an out-of-range setting actually does.
  *
  * ==The task was specified wrongly, and two measurements showed why==
  * The plan said an out-of-range value should make "the affected request" answer `500` rather than `400`,
  * carrying capability 15's server's-fault distinction across. Compaction has **no request path** — its
  * settings are read by a consumer — so the distinction never applied. What was measured instead:
  *
  *  1. **Without protection it is a redelivery storm.** The service started, a user's turn succeeded with
  *     no symptom, `GET /compaction/{id}` returned `404`, and `SessionMemoryConsumer` threw on
  *     construction for every session-memory event — **40 redeliveries in one short test**, because
  *     capability 16 measured that a failing consumer is redelivered without limit. An operator's typo
  *     became a consumer spinning for ever while every outward sign said the service was healthy.
  *  2. **Fail-fast is not available.** `Bootstrap.onStartup` was made to validate and raise — and the
  *     runtime **logs it and starts anyway**: `testKit.start()` returns normally. So a bad value cannot be
  *     made to stop the service the way capability 12's misspelled guardrail class does. That is a finding
  *     about the SDK, not a choice.
  *
  * ==So the design is: loud once, harmless thereafter==
  * `Bootstrap` still raises at startup, where the offending key and value are logged once. The consumer
  * and the store degrade to "compaction off" rather than throwing. A misconfigured service therefore
  * behaves **exactly as if compaction were disabled** — which is what this test asserts, because it is the
  * one observable consequence.
  */
@Tag("slow")
class BadConfigIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val chat = new TestModelProvider()
  private val summariser = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      // Out of range: above the 256 KiB ceiling that keeps compaction below the SDK's own 510 KiB
      // eviction. A threshold this size would also be crossed by the drive below, if it were honoured.
      .withAdditionalConfig("compaction.max-bytes = 512 KiB")
      .withModelProvider(classOf[ChatAgent], chat)
      .withModelProvider(classOf[CompactionAgent], summariser)

  @Test
  def anOutOfRangeSettingDisablesCompactionRatherThanSpinning(): Unit =
    // Deliberately unscripted: any summariser call would fail the turn and this test would say so.
    val session = "us4-bad-config"
    val padding = "x" * 700
    for i <- 1 to 5 do
      chat.fixedResponse(s"a$i $padding")
      componentClient.forAgent().inSession(session).dynamicCall[String, String]("chat-agent").invoke(s"q$i $padding")

    Thread.sleep(3000)

    val history = new SessionMemoryGateway(componentClient).history(session).messages.asScala.toList
    val status = httpClient.GET(s"/compaction/$session").invoke().status.intValue
    logger.info("US4 >>> out-of-range config: {} messages retained, GET -> {}", history.size, status)

    // Identical to `compaction.enabled = false`: every turn kept, nothing summarised, nothing recorded.
    assertThat(history.size).isEqualTo(10)
    assertThat(status).isEqualTo(404)

  /** The service is still fully usable — this is the half that matters, because the failure mode being
    * replaced was a consumer spinning for ever. */
  @Test
  def theServiceKeepsWorkingNormallyOnABadSetting(): Unit =
    chat.fixedResponse("Still here.")
    val reply =
      componentClient
        .forAgent()
        .inSession("us4-bad-config-usable")
        .dynamicCall[String, String]("chat-agent")
        .invoke("are you there?")
    assertThat(reply).isEqualTo("Still here.")
    assertThat(httpClient.GET("/compaction").invoke().status.intValue).isEqualTo(200)
