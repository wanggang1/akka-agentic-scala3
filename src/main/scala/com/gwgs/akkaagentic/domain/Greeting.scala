package com.gwgs.akkaagentic.domain

import java.time.{Instant, ZoneId}

/** A raw greeting request: the user's name and the message text they sent, each
  * possibly absent.
  *
  * Pure domain value with no Akka dependencies, so it is trivially unit-testable
  * in isolation. Fields are [[Option]] rather than nullable `String`: this domain
  * never sees `null`. The `null`s that Jackson produces for absent JSON properties
  * are converted to `None` at the API/agent boundary (see `GreetingEndpoint`), so
  * partiality is handled once, at the edge, not threaded through the model.
  */
final case class GreetingRequest(user: Option[String], text: Option[String]):

  /** Parse this raw request into a [[ValidGreeting]].
    *
    * Parse-don't-validate: on success the result carries plain, present `String`s,
    * so downstream code never re-checks for absence or blankness.
    *
    * @return
    *   `Right(ValidGreeting)` when both `user` and `text` are present and non-blank,
    *   otherwise `Left(message)` describing the first failing field.
    */
  def validate: Either[String, ValidGreeting] =
    for
      u <- present(user, "user must not be blank")
      t <- present(text, "text must not be blank")
    yield ValidGreeting(u, t)

  private def present(value: Option[String], message: String): Either[String, String] =
    value.filterNot(_.isBlank).toRight(message)

/** A greeting request proven to have present, non-blank fields. */
final case class ValidGreeting(user: String, text: String)

/** The personalized greeting produced for a [[GreetingRequest]]. */
final case class GreetingResponse(greeting: String)

/** Pure, total time-of-day computation.
  *
  * A domain helper with no Akka dependencies, so it is deterministically unit-testable
  * (the mocked model in tests never exercises a real clock). Backs the agent's
  * `@FunctionTool`, which reports the caller's current time-of-day.
  *
  * Labels by local hour: morning 05–11, afternoon 12–16, evening 17–20, night 21–04.
  * Never throws — a blank or unrecognized timezone falls back to UTC.
  */
object TimeOfDay:

  private val DefaultZone: ZoneId = ZoneId.of("UTC")

  /** The time-of-day label for `instant` observed in `zone`. Total: every hour maps to a label. */
  def of(instant: Instant, zone: ZoneId): String =
    instant.atZone(zone).getHour match
      case h if h >= 5 && h <= 11  => "morning"
      case h if h >= 12 && h <= 16 => "afternoon"
      case h if h >= 17 && h <= 20 => "evening"
      case _                       => "night" // 21–23 and 0–4

  /** The current time-of-day for the given IANA timezone id (e.g. `"America/New_York"`).
    *
    * `None` (an absent timezone) or a blank/invalid id falls back to UTC. As with
    * [[GreetingRequest]], any `null` from Jackson is converted to `None` at the
    * boundary, so this function only ever deals with `Option`. Never throws.
    */
  def now(timezone: Option[String]): String =
    of(Instant.now(), resolveZone(timezone))

  /** Resolve an IANA timezone id, defaulting to UTC on absent, blank, or unrecognized input. */
  private def resolveZone(timezone: Option[String]): ZoneId =
    timezone
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(parseZone)
      .getOrElse(DefaultZone)

  private def parseZone(id: String): Option[ZoneId] =
    try Some(ZoneId.of(id))
    catch case _: Exception => None
