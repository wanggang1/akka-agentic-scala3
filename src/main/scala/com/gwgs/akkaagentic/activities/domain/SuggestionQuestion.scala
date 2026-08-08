package com.gwgs.akkaagentic.activities.domain

/** A suggestion request proven to carry a non-blank location, with optional preferences.
  *
  * Parse-don't-validate (like cap-1's `GreetingRequest.validate` and cap-3's `HelpQuestion`):
  * [[SuggestionQuestion.validate]] turns the raw, possibly-absent HTTP fields into either an error
  * message or a `SuggestionQuestion` whose `location` is guaranteed non-blank (and trimmed) and whose
  * `preferences` is normalized to `None` when blank/absent — so downstream code (endpoint, coordinator)
  * never re-checks. Pure domain — no Akka dependencies.
  *
  * `instruction` renders the coordinator's task instruction text from these proven fields, keeping the
  * wording in the domain rather than the endpoint/agent.
  */
final case class SuggestionQuestion(location: String, preferences: Option[String]):

  /** The instruction handed to the coordinator's `SUGGEST` task. */
  def instruction: String =
    val prefs = preferences.getOrElse("no particular preference")
    s"Suggest activities for someone in $location. Preferences: $prefs."

object SuggestionQuestion:

  /** `Right(SuggestionQuestion(...))` when a non-blank `location` is present; otherwise `Left(message)`.
    * An absent or blank `location` fails (no task is started). `preferences` blank/absent → `None`.
    * `.map(_.trim)` normalizes the kept value (strips surrounding whitespace); `.filterNot(_.isBlank)`
    * rejects empty/whitespace-only input (two hops because we both transform and filter).
    */
  def validate(location: Option[String], preferences: Option[String]): Either[String, SuggestionQuestion] =
    location
      .map(_.trim)
      .filterNot(_.isBlank)
      .map(loc => SuggestionQuestion(loc, normalize(preferences)))
      .toRight("location must not be blank")

  /** Trim and drop blank/absent preferences to `None`. */
  private def normalize(preferences: Option[String]): Option[String] =
    preferences.map(_.trim).filterNot(_.isBlank)
