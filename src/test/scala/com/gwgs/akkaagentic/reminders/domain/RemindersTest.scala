package com.gwgs.akkaagentic.reminders.domain

import java.time.Instant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The rules as pure values: every transition returns a new `Reminders` and leaves the old one exactly
  * as it was. That property is what lets `ReminderStore` retry a transition safely under contention —
  * so it is asserted, not assumed. */
class RemindersTest:

  private val at = Instant.parse("2026-09-18T10:00:00Z")
  private val (withOne, _) = Reminders().record("r-1", "stand up", at)

  @Test
  def aTransitionNeverChangesTheValueItWasCalledOn(): Unit =
    val (afterCancel, _) = withOne.cancel("r-1", at)
    assertThat(withOne.get("r-1").map(_.state).orNull).isEqualTo(ReminderState.Pending)
    assertThat(afterCancel.get("r-1").map(_.state).orNull).isEqualTo(ReminderState.Cancelled)

  @Test
  def aNoOpTransitionReturnsTheSameValue(): Unit =
    // Firing an unknown id changes nothing — and returns the identical value, which is what lets the
    // store's compare-and-set commit a no-op without a spurious retry.
    val (after, fired) = withOne.fire("ghost", at)
    assertThat(fired.isDefined).isFalse()
    assertThat(after eq withOne).isTrue()

  @Test
  def terminalWinsWhicheverTransitionCommitsFirst(): Unit =
    val (fired, _) = withOne.fire("r-1", at)
    val (stillFired, outcome) = fired.cancel("r-1", at)
    assertThat(outcome.isInstanceOf[CancelOutcome.AlreadyTerminal]).isTrue()
    assertThat(stillFired eq fired).isTrue()
