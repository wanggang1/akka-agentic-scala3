package com.gwgs.akkaagentic.compaction.application

import com.gwgs.akkaagentic.compaction.domain.CompactionThreshold
import com.typesafe.config.{ConfigException, ConfigFactory}
import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.Test

/** T012 — settings, including the distinction capability 15 had to engineer.
  *
  * An out-of-range value must raise `ConfigException.BadValue` and not an `IllegalArgumentException`,
  * because the SDK reports the latter as the caller's `400` — blaming whoever sent the request for the
  * operator's configuration (`docs/sdk-3.6.0-limitations.md` §7d). The exception TYPE is the assertion.
  */
class CompactionSettingsTest:

  private def config(overrides: String = "") =
    ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load())

  @Test
  def theShippedDefaultsAreInRangeAndEnabled(): Unit =
    val threshold = CompactionSettings.threshold(config())
    assertThat(threshold.enabled).isTrue()
    assertThat(threshold.maxBytes).isEqualTo(131072L)
    assertThat(CompactionSettings.maxSessions(config())).isEqualTo(1000)

  @Test
  def theThresholdIsOverridable(): Unit =
    assertThat(CompactionSettings.threshold(config("compaction.max-bytes = 4096")).maxBytes).isEqualTo(4096L)

  /** A size string, not just a number — `getBytes` accepts `4 KiB`, which is how an operator would write it. */
  @Test
  def theThresholdAcceptsASizeString(): Unit =
    assertThat(CompactionSettings.threshold(config("compaction.max-bytes = 4 KiB")).maxBytes).isEqualTo(4096L)

  @Test
  def compactionCanBeDisabled(): Unit =
    assertThat(CompactionSettings.threshold(config("compaction.enabled = false")).enabled).isFalse()

  @Test
  def aThresholdAboveTheCeilingIsTheServersFaultNotTheCallers(): Unit =
    assertThatThrownBy(() => CompactionSettings.threshold(config("compaction.max-bytes = 512 KiB")))
      .isInstanceOf(classOf[ConfigException.BadValue])

  @Test
  def aThresholdBelowTheFloorIsAlsoRefused(): Unit =
    assertThatThrownBy(() => CompactionSettings.threshold(config("compaction.max-bytes = 16")))
      .isInstanceOf(classOf[ConfigException.BadValue])

  /** The SDK's own bound cannot be configured as ours — see research S-1. */
  @Test
  def theSdkOwnEvictionBoundIsNotAConfigurableThreshold(): Unit =
    assertThatThrownBy(() =>
      CompactionSettings.threshold(config(s"compaction.max-bytes = ${CompactionThreshold.SdkEvictionBytes}")))
      .isInstanceOf(classOf[ConfigException.BadValue])

  @Test
  def anOutOfRangeRetentionIsRefusedTheSameWay(): Unit =
    assertThatThrownBy(() => CompactionSettings.maxSessions(config("compaction.max-sessions = 0")))
      .isInstanceOf(classOf[ConfigException.BadValue])
