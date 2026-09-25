package com.gwgs.akkaagentic.feed.probe

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.approvals.application.ApprovalTasks
import com.gwgs.akkaagentic.feed.application.ActivityStore
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}
import org.slf4j.LoggerFactory

/** Phase 0's discovery probe, reduced to the evidence nothing else carries (FR-013).
  *
  * What it keeps:
  *   - **Q-F** — the TestKit's key-value mock does **not** redeliver a failing message, and loses what was
  *     published behind it. This is the reason capability 16 tests failure on the real projection instead,
  *     so it is asserted here rather than left as a note.
  *   - **Q-G** — a Scala consumer can read the SDK's own runtime-owned `TaskEntity`.
  *
  * What it dropped, and why: the 30 s sampling loops that measured the real redelivery schedule
  * (0, 277, 789, 1719, 3419, 6985, 13976, 27611 ms) and the head-of-line blocking. Those are recorded in
  * specs/018 research Q-D, and re-deriving a known number cost the suite ~2 minutes per run. The behaviour
  * they justified is pinned by `BoundedActivityDeliveryIntegrationTest`, on the real path.
  */
class ConsumerProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(classOf[ConsumerProbeIntegrationTest])

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withKeyValueEntityIncomingMessages(classOf[TodoEntity])

  private def incoming = testKit.getKeyValueEntityIncomingMessages(classOf[TodoEntity])

  @BeforeEach
  def reset(): Unit =
    ProbeLog.clear()
    ActivityStore.clear()

  private def list(items: String*): TodoList = items.foldLeft(TodoList.empty())((l, d) => l.add(d))
  private def seen(consumer: String, subject: String) = ProbeLog.of(consumer, subject)

  @Test
  def aScalaConsumerReceivesStateAndItsMetadata(): Unit =
    incoming.publish(list("buy milk"), "probe-alice")
    Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() => seen("todo", "probe-alice").nonEmpty)
    val o = seen("todo", "probe-alice").head
    logger.info("Q-A >>> received: {}", o.detail)
    logger.info("Q-B >>> metadata: {}", o.metadataKeys.mkString(", "))
    assertThat(o.detail).contains("buy milk")
    // ce-subject identifies the entity; ce-id is per DELIVERY, which is why it cannot key idempotence.
    assertThat(o.metadataKeys.mkString(",")).contains("ce-subject")

  /** Q-F — the mocked channel **never redelivers** a failing message. That alone makes any failure
    * assertion written against it a false green, and is why every failure test in this capability runs on
    * the real projection instead.
    *
    * A second Phase 0 observation — that messages published *behind* the failure were lost — did **not**
    * reproduce here (the bystander arrived), so it is logged rather than asserted, and research.md now
    * records both runs. What reproduces is the absence of redelivery. */
  @Test
  def theMockedChannelDoesNotRedeliverAFailingMessage(): Unit =
    ProbeLog.poison("probe-poison")
    incoming.publish(list("doomed"), "probe-poison")
    Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() => seen("todo", "probe-poison").nonEmpty)
    incoming.publish(list("ordinary"), "probe-bystander")

    Thread.sleep(4000) // long enough for the real projection to have redelivered several times
    val deliveries = seen("todo", "probe-poison").size
    val bystanderArrived = seen("todo", "probe-bystander").nonEmpty
    logger.info("Q-F >>> mocked path: poison deliveries={} (real path: many) bystander arrived={} (real path: eventually)",
      deliveries, bystanderArrived)
    assertThat(deliveries).isEqualTo(1) // no redelivery at all — the real projection redelivers ~8 times in this window
    // `bystanderArrived` is deliberately NOT asserted: observed lost in Phase 0, delivered here.

  @Test
  def aDuplicateIsDeliveredTwiceByThePlatform(): Unit =
    // At-least-once is documented; this is why the feed's idempotence is its own rule (D5).
    val same = list("once")
    incoming.publish(same, "probe-dup")
    incoming.publish(same, "probe-dup")
    Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() => seen("todo", "probe-dup").size >= 2)
    logger.info("Q-E >>> identical state published twice -> deliveries: {}", seen("todo", "probe-dup").size)

  /** Q-G — a Scala consumer reads the SDK's own runtime-owned `TaskEntity`. Reachable; and measured live to
    * be useless for fork B3, because capability 7's delegation to request-based specialists creates no
    * tasks (research Q-G). */
  @Test
  def aScalaConsumerCanReadTheRuntimeOwnedTaskEntity(): Unit =
    val taskId = s"probe-task-${UUID.randomUUID().toString.take(8)}"
    componentClient.forTask(taskId).create(ApprovalTasks.APPROVAL.instructions("probe: approve or reject"))
    componentClient.forTask(taskId).assign("reviewer")
    componentClient.forTask(taskId).fail("probe: rejected on purpose")
    Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() => seen("task", taskId).size >= 3)
    val details = seen("task", taskId).map(_.detail).mkString(" | ")
    logger.info("Q-G >>> {}", details)
    assertThat(details).contains("TaskAssigned")
    assertThat(details).contains("assignee=reviewer")
