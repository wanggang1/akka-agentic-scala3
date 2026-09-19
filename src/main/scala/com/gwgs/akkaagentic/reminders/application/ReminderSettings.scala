package com.gwgs.akkaagentic.reminders.application

import com.typesafe.config.{Config, ConfigException}

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
    * silently corrected setting is one an operator believes is in force when it is not.
    *
    * **Why `ConfigException.BadValue` and not `require`** — found walking the quickstart live. `require`
    * throws `IllegalArgumentException`, and the SDK reports an endpoint that fails to construct with one
    * as **`400 Bad Request`**: a server misconfiguration told to the *caller* as their mistake, with an
    * internal setting's name in the body. A bad setting is the server's fault, and Typesafe Config has an
    * exception type for exactly this. */
  def maxRetries(config: Config): Int =
    val value = config.getInt(MaxRetriesKey)
    if value < 1 || value > MaxRetriesCeiling then
      throw ConfigException.BadValue(MaxRetriesKey, s"must be between 1 and $MaxRetriesCeiling, was $value")
    value
