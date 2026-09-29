package com.gwgs.akkaagentic.compaction.application

import com.gwgs.akkaagentic.compaction.domain.CompactionThreshold
import com.typesafe.config.{Config, ConfigException}

/** The capability's operational settings, read in one place so the trigger and the store cannot disagree.
  *
  * The domain returns an `Either` for an out-of-range threshold; translating that `Left` is this layer's
  * job, and it throws **`ConfigException.BadValue`** rather than `require`. The distinction is not
  * cosmetic: the SDK reports a component that fails to construct with an `IllegalArgumentException` as the
  * caller's `400`, which blames whoever sent the request for the operator's configuration — capability 15
  * had to engineer its way out of exactly that (`docs/sdk-3.6.0-limitations.md` §7d).
  */
object CompactionSettings:

  val MaxBytesKey = "compaction.max-bytes"
  val EnabledKey = "compaction.enabled"
  val MaxSessionsKey = "compaction.max-sessions"

  def threshold(config: Config): CompactionThreshold =
    CompactionThreshold
      .of(config.getBytes(MaxBytesKey), config.getBoolean(EnabledKey))
      .fold(reason => throw ConfigException.BadValue(MaxBytesKey, reason), identity)

  /** The threshold, or **compaction switched off** if the configuration is out of range.
    *
    * Measured (specs/019 T029): a consumer that throws on an out-of-range value is reconstructed and
    * throws again for **every** session-memory event — 40 redeliveries in one short test — because
    * capability 16 measured that a failing consumer is redelivered without limit. That turns an operator's
    * typo into a consumer spinning for ever, with no outward symptom.
    *
    * And the obvious fix is not available: `Bootstrap.onStartup` DOES validate and DOES raise, but the
    * runtime logs that and starts anyway (measured — `testKit.start()` returns normally), so a bad value
    * cannot be made to stop the service the way capability 12's misspelled guardrail class does.
    *
    * So the failure is made **loud once and harmless thereafter**: `Bootstrap` raises at startup, where it
    * is logged with the offending key and value, and the consumer degrades to "compaction off" instead of
    * spinning. A misconfigured service does not compact, says so at startup, and damages nothing.
    */
  def thresholdOrDisabled(config: Config): CompactionThreshold =
    try threshold(config)
    catch
      case bad: ConfigException.BadValue =>
        CompactionThreshold
          .of(CompactionThreshold.MaxBytes, enabled = false)
          .getOrElse(throw bad) // unreachable: MaxBytes is in range by construction

  def maxSessions(config: Config): Int =
    val value = config.getInt(MaxSessionsKey)
    if value < 1 || value > 100000 then
      throw ConfigException.BadValue(MaxSessionsKey, s"must be between 1 and 100000, was $value")
    value
