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

  def maxSessions(config: Config): Int =
    val value = config.getInt(MaxSessionsKey)
    if value < 1 || value > 100000 then
      throw ConfigException.BadValue(MaxSessionsKey, s"must be between 1 and 100000, was $value")
    value
