package com.gwgs.akkaagentic.compaction.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T009 — the whole "should this be compacted?" rule, with no runtime and no model. */
class CompactionDecisionTest:

  private def threshold(bytes: Long, enabled: Boolean = true): CompactionThreshold =
    CompactionThreshold.of(bytes, enabled).toOption.get

  @Test
  def belowTheThresholdIsLeftAlone(): Unit =
    assertThat(CompactionDecision.decide(4095, threshold(4096))).isEqualTo(Decision.Leave)

  @Test
  def atTheThresholdCompacts(): Unit =
    assertThat(CompactionDecision.decide(4096, threshold(4096))).isEqualTo(Decision.Compact(4096))

  @Test
  def aboveTheThresholdCompactsAndCarriesTheSizeThatCrossedIt(): Unit =
    assertThat(CompactionDecision.decide(9001, threshold(4096))).isEqualTo(Decision.Compact(9001))

  @Test
  def disabledNeverCompactsHoweverLargeTheHistory(): Unit =
    assertThat(CompactionDecision.decide(Long.MaxValue, threshold(4096, enabled = false)))
      .isEqualTo(Decision.Leave)

  @Test
  def theFloorIsRejected(): Unit =
    assertThat(CompactionThreshold.of(CompactionThreshold.MinBytes - 1, true).isLeft).isTrue()

  /** The ceiling is the load-bearing bound: above it the SDK's own 510 KiB eviction reaches the oldest
    * turns before compaction ever runs, so compaction would summarise a history whose beginning is gone
    * (research S-1). A threshold of 510 KiB must therefore be impossible to configure. */
  @Test
  def theCeilingIsRejectedAndSitsBelowTheSdkOwnEvictionBound(): Unit =
    assertThat(CompactionThreshold.of(CompactionThreshold.MaxBytes + 1, true).isLeft).isTrue()
    assertThat(CompactionThreshold.of(CompactionThreshold.SdkEvictionBytes, true).isLeft).isTrue()
    assertThat(CompactionThreshold.MaxBytes < CompactionThreshold.SdkEvictionBytes).isTrue()

  @Test
  def theBoundsThemselvesAreAccepted(): Unit =
    assertThat(CompactionThreshold.of(CompactionThreshold.MinBytes, true).isRight).isTrue()
    assertThat(CompactionThreshold.of(CompactionThreshold.MaxBytes, true).isRight).isTrue()
