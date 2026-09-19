package com.gwgs.akkaagentic.feed.probe

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.approvals.application.ApprovalTasks
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}
import org.slf4j.LoggerFactory

/** Phase 0 probe for capability 16 (specs/018), the TestKit's MOCKED-incoming path. Every verdict is
  * logged verbatim ("Q-x >>>") so research.md quotes rather than paraphrases. No model anywhere.
  */
class ConsumerProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(classOf[ConsumerProbeIntegrationTest])

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withKeyValueEntityIncomingMessages(classOf[TodoEntity])
      .withTopicOutgoingMessages("todo-activity-probe")

  private def incoming = testKit.getKeyValueEntityIncomingMessages(classOf[TodoEntity])
  private def outgoing = testKit.getTopicOutgoingMessages("todo-activity-probe")

  @BeforeEach
  def reset(): Unit =
    ProbeLog.clear()
    outgoing.clear()

  private def list(items: String*): TodoList = items.foldLeft(TodoList.empty())((l, d) => l.add(d))
  private def seen(consumer: String, subject: String) = ProbeLog.of(consumer, subject)
  private def awaitSeen(consumer: String, subject: String, atLeast: Int = 1, seconds: Int = 10): Unit =
    Awaitility.await().atMost(seconds, TimeUnit.SECONDS).until(() => seen(consumer, subject).size >= atLeast)

  @Test
  def qA_aScalaConsumerLoadsAndReceives(): Unit =
    incoming.publish(list("buy milk"), "alice")
    awaitSeen("todo", "alice")
    val o = seen("todo", "alice").head
    logger.info("Q-A >>> received: {}", o.detail)
    logger.info("Q-B >>> metadata keys on a delivery: {}", o.metadataKeys.mkString(", "))

  @Test
  def qC_produceToATopicFromScala(): Unit =
    incoming.publish(list("buy milk", "call mum"), "bob")
    val raw = outgoing.expectOneRaw(Duration.ofSeconds(10))
    val keys = raw.getMetadata.asScala.map(e => s"${e.getKey}=${if e.isText then e.getValue else "<bin>"}").toList.sorted
    logger.info("Q-C >>> produced payload: {}", raw.getPayload.toString(UTF_8))
    logger.info("Q-C >>> produced metadata: {}", keys.mkString(", "))

  @Test
  def qD_aFailingDeliveryIsRetried_andWhatItBlocks(): Unit =
    ProbeLog.poison("poison-1")
    incoming.publish(list("first"), "poison-1")
    awaitSeen("todo", "poison-1")
    // A LATER message for the SAME entity, and one for a DIFFERENT entity, while the first keeps failing.
    incoming.publish(list("first", "second"), "poison-1")
    incoming.publish(list("x"), "bystander")
    val samples = (1 to 6).map { i =>
      Thread.sleep(5000)
      val p = seen("todo", "poison-1")
      s"${i * 5}s: attempts=${p.size} lastDetail=${p.lastOption.map(_.detail.takeRight(30)).getOrElse("-")} bystanderSeen=${seen("todo", "bystander").nonEmpty}"
    }
    samples.foreach(s => logger.info("Q-D >>> {}", s))
    ProbeLog.cure("poison-1")
    Thread.sleep(8000)
    logger.info("Q-D >>> after cure: poison-1 details in order = {}", seen("todo", "poison-1").map(_.detail.takeRight(30)).distinct.mkString(" | "))
    logger.info("Q-D >>> after cure: bystander seen = {}", seen("todo", "bystander").nonEmpty)
    logger.info("Q-D >>> attempt timestamps (ms since first): {}", {
      val ts = seen("todo", "poison-1").map(_.atMillis); ts.map(_ - ts.head).mkString(", ") })

  @Test
  def qD_aSelfBoundedGiveUpUnblocksTheStream(): Unit =
    ProbeLog.giveUpAfter(3)
    ProbeLog.poison("poison-2")
    incoming.publish(list("doomed"), "poison-2")
    incoming.publish(list("after"), "bystander-2")
    val bystanderArrived =
      try { awaitSeen("todo", "bystander-2", seconds = 40); true } catch case _: Throwable => false
    logger.info("Q-D(c) >>> giveUpAfter=3: poison-2 attempts={} bystander-2 arrived={}", seen("todo", "poison-2").size, bystanderArrived)

  @Test
  def qE_aDuplicateIsDeliveredTwice(): Unit =
    val same = list("once")
    incoming.publish(same, "dup")
    incoming.publish(same, "dup")
    Thread.sleep(3000)
    logger.info("Q-E >>> identical state published twice -> deliveries: {}", seen("todo", "dup").size)

  @Test
  def deleteReachesTheDeleteHandler(): Unit =
    incoming.publishDelete("carol")
    awaitSeen("todo", "carol")
    logger.info("Q-A >>> delete: {}", seen("todo", "carol").map(o => s"${o.kind}/${o.detail}").mkString(", "))

  @Test
  def qG_canAScalaConsumerReadTheRuntimeOwnedTaskEntity(): Unit =
    val taskId = s"probe-task-${UUID.randomUUID().toString.take(8)}"
    componentClient.forTask(taskId).create(ApprovalTasks.APPROVAL.instructions("probe: approve or reject"))
    componentClient.forTask(taskId).assign("reviewer")
    componentClient.forTask(taskId).fail("probe: rejected on purpose")
    val arrived =
      try { Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() => seen("task", taskId).size >= 3); true }
      catch case _: Throwable => false
    logger.info("Q-G >>> task events reached the Scala consumer? {} -> {}", arrived, seen("task", taskId).map(_.detail).mkString(" | "))
