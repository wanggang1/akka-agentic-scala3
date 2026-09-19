package com.gwgs.akkaagentic.reminders.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T006 — the four states stay four, and terminal means terminal (FR-003, FR-006, SC-004). */
class ReminderStateTest:

  @Test
  def onlyPendingIsNonTerminal(): Unit =
    assertThat(ReminderState.Pending.isTerminal).isFalse()
    assertThat(ReminderState.Fired.isTerminal).isTrue()
    assertThat(ReminderState.Cancelled.isTerminal).isTrue()
    assertThat(ReminderState.Failed.isTerminal).isTrue()

  @Test
  def pendingMayBecomeAnyTerminalState(): Unit =
    assertThat(ReminderState.Pending.canTransitionTo(ReminderState.Fired)).isTrue()
    assertThat(ReminderState.Pending.canTransitionTo(ReminderState.Cancelled)).isTrue()
    assertThat(ReminderState.Pending.canTransitionTo(ReminderState.Failed)).isTrue()

  @Test
  def pendingNeverTransitionsToItself(): Unit =
    assertThat(ReminderState.Pending.canTransitionTo(ReminderState.Pending)).isFalse()

  @Test
  def noTerminalStateEverMovesAgain(): Unit =
    // This is what lets a cancel-after-fired be reported as what it is rather than as a success.
    val terminals = List(ReminderState.Fired, ReminderState.Cancelled, ReminderState.Failed)
    for from <- terminals; to <- ReminderState.values do
      assertThat(from.canTransitionTo(to)).isFalse()

  @Test
  def theWireSpellingIsLowercaseAndDistinct(): Unit =
    // A Scala List is not a java.util.List, so AssertJ would resolve to ObjectAssert and
    // containsExactly would not exist — compare the rendered sequence instead.
    val labels = ReminderState.values.map(_.label).toList
    assertThat(labels.mkString(",")).isEqualTo("pending,fired,cancelled,failed")
    assertThat(labels.distinct.size).isEqualTo(labels.size)
