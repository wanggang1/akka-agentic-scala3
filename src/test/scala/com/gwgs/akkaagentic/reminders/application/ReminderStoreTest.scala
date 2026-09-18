package com.gwgs.akkaagentic.reminders.application

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import com.gwgs.akkaagentic.reminders.domain.{CancelOutcome, ReminderState}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** T008 — the store's three cancel outcomes and its terminal-wins rule (FR-006).
  *
  * No runtime and no timers here: this is the pure bookkeeping that the endpoints translate into
  * `200` / `409` / `404`. Proving it offline means the integration tests only have to show that the
  * translation is wired correctly, not that the rule is right.
  */
class ReminderStoreTest:

  @BeforeEach
  def reset(): Unit = ReminderStore.clear()

  @Test
  def aRecordedReminderIsPendingAndCarriesItsNote(): Unit =
    val reminder = ReminderStore.record("r-1", "stand up")
    assertThat(reminder.state).isEqualTo(ReminderState.Pending)
    assertThat(reminder.note).isEqualTo("stand up")
    assertThat(ReminderStore.get("r-1").map(_.note).orNull).isEqualTo("stand up")

  @Test
  def cancellingAPendingReminderReportsCancelled(): Unit =
    ReminderStore.record("r-2", "cancel me")
    ReminderStore.cancel("r-2") match
      case CancelOutcome.Cancelled(reminder) =>
        assertThat(reminder.state).isEqualTo(ReminderState.Cancelled)
        assertThat(reminder.cancelledAt.isDefined).isTrue()
      case other => throw AssertionError(s"expected Cancelled but got $other")

  @Test
  def cancellingAFiredReminderReportsTheStateItIsActuallyIn(): Unit =
    // The failure this guards against: answering "cancelled" to a request that cancelled nothing.
    ReminderStore.record("r-3", "already fired")
    ReminderStore.markFired("r-3")
    ReminderStore.cancel("r-3") match
      case CancelOutcome.AlreadyTerminal(reminder) =>
        assertThat(reminder.state).isEqualTo(ReminderState.Fired)
      case other => throw AssertionError(s"expected AlreadyTerminal(Fired) but got $other")

  @Test
  def cancellingTwiceReportsAlreadyTerminalTheSecondTime(): Unit =
    ReminderStore.record("r-4", "twice")
    assertThat(ReminderStore.cancel("r-4").isInstanceOf[CancelOutcome.Cancelled]).isTrue()
    ReminderStore.cancel("r-4") match
      case CancelOutcome.AlreadyTerminal(reminder) =>
        assertThat(reminder.state).isEqualTo(ReminderState.Cancelled)
      case other => throw AssertionError(s"expected AlreadyTerminal(Cancelled) but got $other")

  @Test
  def cancellingAnUnknownHandleReportsUnknown(): Unit =
    assertThat(ReminderStore.cancel("never-existed")).isEqualTo(CancelOutcome.Unknown)

  @Test
  def aCancelledReminderCannotThenFire(): Unit =
    // The fire-versus-cancel window: the cancel got there first, so firing must not overwrite it.
    ReminderStore.record("r-5", "raced")
    ReminderStore.cancel("r-5")
    assertThat(ReminderStore.markFired("r-5").isDefined).isFalse()
    assertThat(ReminderStore.get("r-5").map(_.state).orNull).isEqualTo(ReminderState.Cancelled)

  @Test
  def aFiredReminderCannotThenFail(): Unit =
    ReminderStore.record("r-6", "done")
    ReminderStore.markFired("r-6")
    assertThat(ReminderStore.markFailed("r-6", "too late").isDefined).isFalse()
    assertThat(ReminderStore.get("r-6").map(_.state).orNull).isEqualTo(ReminderState.Fired)

  @Test
  def failingRecordsTheReasonAndCountsTheAttempt(): Unit =
    ReminderStore.record("r-7", "doomed")
    ReminderStore.recordAttempt("r-7")
    val failed = ReminderStore.markFailed("r-7", "deliberate failure")
    assertThat(failed.map(_.state).orNull).isEqualTo(ReminderState.Failed)
    assertThat(failed.flatMap(_.failure).orNull).isEqualTo("deliberate failure")
    assertThat(failed.map(_.attempts).getOrElse(0)).isEqualTo(2)

  @Test
  def transitionsOnAnUnknownHandleDoNothing(): Unit =
    assertThat(ReminderStore.markFired("ghost").isDefined).isFalse()
    assertThat(ReminderStore.markFailed("ghost", "reason").isDefined).isFalse()
    assertThat(ReminderStore.recordAttempt("ghost").isDefined).isFalse()
    assertThat(ReminderStore.get("ghost").isDefined).isFalse()

  @Test
  def concurrentCancelsOfOneReminderHaveExactlyOneWinner(): Unit =
    // The claim the single-cell design makes, tested with real threads rather than argued: however
    // many callers race to cancel the same pending reminder, exactly one is told it cancelled it.
    val racers = 32
    ReminderStore.record("r-race", "contended")
    val pool = Executors.newFixedThreadPool(racers)
    val start = CountDownLatch(1)
    try
      val futures = (1 to racers).map { _ =>
        pool.submit(() => { start.await(); ReminderStore.cancel("r-race") })
      }
      start.countDown()
      val outcomes = futures.map(_.get(10, TimeUnit.SECONDS))
      assertThat(outcomes.count(_.isInstanceOf[CancelOutcome.Cancelled])).isEqualTo(1)
      assertThat(outcomes.count(_.isInstanceOf[CancelOutcome.AlreadyTerminal])).isEqualTo(racers - 1)
    finally pool.shutdownNow()
