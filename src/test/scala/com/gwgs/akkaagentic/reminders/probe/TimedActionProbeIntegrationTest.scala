package com.gwgs.akkaagentic.reminders.probe

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.reminders.application.ReminderStore
import com.gwgs.akkaagentic.reminders.domain.ReminderState
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}
import org.slf4j.LoggerFactory

/** Phase 0 discovery probe for capability 15 (specs/017). Answers Q-A, Q-B, Q-D and Q-E by
  * measurement; every verdict is logged verbatim so research.md can quote it rather than paraphrase.
  *
  * No model anywhere — a reminder carries a note, and needs no language model to do it.
  */
class TimedActionProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(classOf[TimedActionProbeIntegrationTest])

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT.withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")

  @BeforeEach
  def reset(): Unit = ReminderStore.clear()

  /** Observed through the capability's own store — the probe's ReminderLog instrument is retired. */
  private def hasFired(name: String): Boolean = ReminderStore.get(name).exists(_.state == ReminderState.Fired)
  private def attempts(name: String): Int = ReminderStore.get(name).map(_.attempts).getOrElse(0)

  private def post(path: String): String =
    httpClient.POST(path).invoke().body().utf8String

  private def unique(prefix: String) = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  /** Q-A — can a Scala caller schedule at all? Compiles (verified separately); this is run time. */
  @Test
  def recordWhetherScalaCanSchedule(): Unit =
    val name = unique("scala")
    val verdict = post(s"/probe/scala-schedule/$name/300")
    logger.info("Q-A probe >>> Scala schedule verdict: {}", verdict)

    val firedWithin2s =
      try
        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() => hasFired(name))
        true
      catch case _: Throwable => false
    logger.info("Q-A probe >>> did the Scala-scheduled timer fire within 2s? {}", firedWithin2s)

    // Recorded, not demanded: the probe exists to find out which it is.
    assertThat(verdict).isNotEmpty()

  /** Q-A control — the same schedule from Java. If this fires and the Scala one does not, the finding
    * is about Scala, not about our usage. */
  @Test
  def theJavaPathIsTheControl(): Unit =
    val name = unique("java")
    val verdict = post(s"/probe/java-schedule/$name/300")
    logger.info("Q-A control >>> Java schedule verdict: {}", verdict)

    val fired =
      try
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() => hasFired(name))
        true
      catch case _: Throwable => false
    logger.info("Q-A control >>> Java-scheduled timer fired? {} (note carried = {})",
      fired, ReminderStore.get(name).map(_.note))
    assertThat(verdict).isNotEmpty()

  /** Q-B — cancel from Scala a timer that Java scheduled. If scheduling is Java-only and cancelling is
    * not, ONE FAMILY sits on both sides of the wall, which is the headline. */
  @Test
  def recordWhetherScalaCanCancelAJavaScheduledTimer(): Unit =
    val name = unique("cancelme")
    val scheduled = post(s"/probe/java-schedule/$name/1500")
    val cancelled = post(s"/probe/scala-cancel/$name")
    logger.info("Q-B probe >>> scheduled={} cancelled={}", scheduled, cancelled)

    // Wait past the delay: if cancellation worked, it never fires.
    Thread.sleep(2500)
    val firedAnyway = hasFired(name)
    logger.info("Q-B probe >>> after waiting past the delay, fired = {} (false means cancel worked)",
      firedAnyway)
    assertThat(cancelled).isNotEmpty()

  /** Q-D — the retry contract, COUNTED. FR-008 forbids shipping something that can retry for ever, so
    * the default and the bounded overload are both measured. */
  @Test
  def countRetriesOnAFailingAction(): Unit =
    val bounded = unique("bounded")
    post(s"/probe/java-schedule-failing/$bounded/300/2")
    Thread.sleep(6000)
    val boundedAttempts = attempts(bounded)

    logger.info("Q-D probe >>> attempts with maxRetries=2 after 6s: {}", boundedAttempts)

    // The 3-arg default is NOT sampled here any more. It was measured once, over 30s, and kept
    // climbing with widening gaps: 5s=2 10s=3 15s=3 20s=3 25s=4 30s=4 — i.e. unbounded, which is
    // why FR-008 makes the 4-arg overload mandatory. Re-running that sampling loop every build
    // would cost the suite 30 seconds to re-derive a number research.md already records, so the
    // permanent test keeps only the half that pins a SHIPPING rule: maxRetries bounds the attempts.
    assertThat(boundedAttempts).isLessThanOrEqualTo(2)

  /** Q-E — how small can a delay be and still be reliable? The suite's cost depends on it. */
  @Test
  def measureTheSmallestReliableDelay(): Unit =
    val timings = List(100, 300, 1000).map { ms =>
      val name = unique(s"delay$ms")
      val start = System.nanoTime()
      post(s"/probe/java-schedule/$name/$ms")
      val fired =
        try
          Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() => hasFired(name))
          (System.nanoTime() - start) / 1_000_000
        catch case _: Throwable => -1L
      s"requested ${ms}ms -> fired after ${fired}ms"
    }
    logger.info("Q-E probe >>> {}", timings.mkString(" | "))
    assertThat(timings.size).isEqualTo(3)
