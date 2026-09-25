package com.gwgs.akkaagentic.feed.application

import com.gwgs.akkaagentic.feed.domain.FeedLimits
import com.typesafe.config.{Config, ConfigException}

/** The capability's operational settings, read in one place so the consumer and the store cannot disagree.
  *
  * Out-of-range values are refused with `ConfigException.BadValue`, **not** `require`: the SDK reports a
  * component that fails to construct with an `IllegalArgumentException` as the caller's `400`, which blames
  * whoever sent the request for the server's configuration (capability 15, `docs/sdk-3.6.0-limitations.md`
  * §7d).
  */
object FeedSettings:

  val MaxAttemptsKey = "feed.max-attempts"
  val MaxEntriesKey = "feed.max-entries"
  val MaxSetAsidesKey = "feed.max-set-asides"
  val MaxUsersKey = "feed.max-users"

  /** How many times one delivery may be attempted before it is set aside (research Q-D). */
  def maxAttempts(config: Config): Int = bounded(config, MaxAttemptsKey, 1, 10)

  def limits(config: Config): FeedLimits =
    FeedLimits(
      maxEntries = bounded(config, MaxEntriesKey, 1, 10000),
      maxSetAsides = bounded(config, MaxSetAsidesKey, 1, 1000),
      maxUsers = bounded(config, MaxUsersKey, 1, 100000))

  private def bounded(config: Config, key: String, min: Int, max: Int): Int =
    val value = config.getInt(key)
    if value < min || value > max then
      throw ConfigException.BadValue(key, s"must be between $min and $max, was $value")
    value
