package com.gwgs.akkaagentic.activities.domain

/** A tiny, canned weather source the `WeatherSpecialist` consults.
  *
  * Pure domain — no Akka dependencies, no network (spec FR-010): the point is deterministic, offline
  * conditions so the capability is reproducible in tests, not a real forecast. Lookup is
  * case-insensitive and **total**: an unknown location yields a sensible default rather than throwing
  * (spec edge case), so the specialist always has something to report. Mirrors cap-3's `KnowledgeBase`.
  */
object WeatherData:

  /** A default for locations we have no canned entry for — keeps the lookup total. */
  private val Default = "mild and partly cloudy, around 18°C"

  /** Known conditions, keyed by normalized (trimmed, lower-cased) location name. */
  private val conditions: Map[String, String] = Map(
    "boston" -> "clear skies, around 20°C",
    "london" -> "light rain, around 12°C",
    "phoenix" -> "hot and sunny, around 38°C",
    "reykjavik" -> "cold and windy, around 3°C",
    "singapore" -> "humid with afternoon thunderstorms, around 31°C"
  )

  /** The conditions summary for a location, case-insensitively; the default for unknown locations. */
  def forLocation(location: String): String =
    Option(location).map(normalize).flatMap(conditions.get).getOrElse(Default)

  /** All known location keys (normalized) — handy for tests. */
  def knownLocations: Set[String] = conditions.keySet

  private def normalize(location: String): String = location.trim.toLowerCase
