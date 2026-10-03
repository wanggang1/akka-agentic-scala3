package com.gwgs.akkaagentic.compaction.probe

import java.util.concurrent.TimeUnit

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** Phase 0 probe, trimmed to the one measurement nothing in production carries (T036).
  *
  * **R-1 — a Scala consumer over the RUNTIME-OWNED `SessionMemoryEntity` matches its `Event` records.**
  * Capability 16 proved the analogous thing for `TaskEntity`; this extends capability 13's clause (the
  * wall is about *which client*, not who owns the component) to a second runtime-owned event stream.
  *
  * Production cannot assert this. `SessionMemoryConsumer` has a `case _ =>` default, exactly as it should,
  * so an event it failed to match would be silently ignored rather than reported — a green suite would
  * look identical. The probe keeps a record of every event kind it saw, so "zero unmatched" is checkable.
  *
  * The other Phase 0 measurements were retired once production covered them: **R-2** (compaction does not
  * re-trigger on its own write) by `NoSelfTriggerIntegrationTest`, and **R-4** by
  * `StaleCompactionIntegrationTest` — which also *corrected* it: what the probe recorded as a silently
  * discarded write was in fact a merge, misread from a message count that coincidentally matched. The
  * turn-alignment measurement stays in `EvictionAlignmentProbeIntegrationTest`, where nothing else
  * asserts it.
  */
class CompactionProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[ChatAgent], model)

  @Test
  def r1_aScalaConsumerReceivesAndMatchesSessionMemoryEvents(): Unit =
    val session = "probe-r1"
    for (message, reply) <- List("my name is Ada" -> "Nice to meet you, Ada!", "what is my name?" -> "Ada.")
    do
      model.fixedResponse(reply)
      componentClient.forAgent().inSession(session).dynamicCall[String, String]("chat-agent").invoke(message)

    Awaitility
      .await()
      .atMost(15, TimeUnit.SECONDS)
      .until(() => SessionMemoryProbeConsumer.seen(session).size >= 4)

    val seen = SessionMemoryProbeConsumer.seen(session)
    logger.info("R-1 >>> events seen for [{}]: {}", session, seen.mkString(" | "))
    assertThat(seen.mkString(" | ")).contains("UserMessageAdded")
    assertThat(seen.mkString(" | ")).contains("AiMessageAdded")
    assertThat(seen.count(_.startsWith("UNMATCHED"))).isEqualTo(0)
