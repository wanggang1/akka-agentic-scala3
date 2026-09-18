package com.gwgs.akkaagentic.reminders.application

import com.typesafe.config.Config

/** The capability's one operational setting, read in one place so the timer and the action cannot
  * disagree about it.
  *
  * `maxRetries` is used twice, deliberately: as the timer's `maxRetries` (the runtime's bound) and as
  * the attempt limit a timed action enforces on itself (our bound). The second exists because the SDK
  * gives an action no attempt number and a timer that runs out of retries tells nobody — so without it,
  * a reminder whose work always failed would read `pending` for ever.
  */
object ReminderSettings:

  val MaxRetriesKey: String = "reminders.max-retries"

  /** Bounded above as well as below: "bounded" should also mean *cheap to be wrong about*. */
  val MaxRetriesCeiling: Int = 10

  /** Reads and checks the bound. Out-of-range values are refused outright rather than clamped — a
    * silently corrected setting is one an operator believes is in force when it is not. */
  def maxRetries(config: Config): Int =
    val value = config.getInt(MaxRetriesKey)
    require(
      value >= 1 && value <= MaxRetriesCeiling,
      s"$MaxRetriesKey must be between 1 and $MaxRetriesCeiling, was $value")
    value
