package com.gwgs.akkaagentic.reminders.application

import com.gwgs.akkaagentic.reminders.application.BoundedAttempts.Outcome
import com.gwgs.akkaagentic.reminders.domain.ReminderState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** The bound every timed action in this capability runs its work under — tested with no timers and no
  * runtime. `BoundedRetryIntegrationTest` then shows the runtime honouring it end to end. */
class BoundedAttemptsTest:

  private val boom = RuntimeException("boom")
  private def state(id: String) = ReminderStore.get(id).map(_.state).orNull
  private def attempts(id: String) = ReminderStore.get(id).map(_.attempts).getOrElse(-1)

  @BeforeEach
  def reset(): Unit = ReminderStore.clear()

  @Test
  def workThatSucceedsIsReportedAsSuch(): Unit =
    ReminderStore.record("ok", "note")
    val outcome = BoundedAttempts.run("ok", limit = 2)(ReminderStore.markFired("ok"))
    assertThat(outcome).isEqualTo(Outcome.Succeeded)
    assertThat(state("ok")).isEqualTo(ReminderState.Fired)

  @Test
  def aFailureWithAttemptsLeftAsksForARetryAndCountsIt(): Unit =
    ReminderStore.record("r", "note")
    BoundedAttempts.run("r", limit = 3)(throw boom) match
      case Outcome.WillRetry(attempt, cause) =>
        assertThat(attempt).isEqualTo(1)
        assertThat(cause).isSameAs(boom)
      case other => throw AssertionError(s"expected WillRetry, got $other")
    assertThat(state("r")).isEqualTo(ReminderState.Pending)
    assertThat(attempts("r")).isEqualTo(1)

  @Test
  def theLastPermittedFailureRecordsFailedAndStops(): Unit =
    // The point of the helper: bounded AND said out loud. Without it the reminder would stay `pending`.
    ReminderStore.record("r", "note")
    BoundedAttempts.run("r", limit = 2)(throw boom)
    BoundedAttempts.run("r", limit = 2)(throw boom) match
      case Outcome.GaveUp(attempt, _) => assertThat(attempt).isEqualTo(2)
      case other                      => throw AssertionError(s"expected GaveUp, got $other")
    assertThat(state("r")).isEqualTo(ReminderState.Failed)
    assertThat(attempts("r")).isEqualTo(2)
    assertThat(ReminderStore.get("r").flatMap(_.failure).orNull).contains("boom")

  @Test
  def aReminderThatCannotFireIsNotAFailure(): Unit =
    // Cancelled first: the work returns normally (markFired is a no-op), so nothing is retried.
    ReminderStore.record("c", "note")
    ReminderStore.cancel("c")
    assertThat(BoundedAttempts.run("c", limit = 2)(ReminderStore.markFired("c"))).isEqualTo(Outcome.Succeeded)
    assertThat(state("c")).isEqualTo(ReminderState.Cancelled)
