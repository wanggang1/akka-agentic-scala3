package com.gwgs.akkaagentic.assistant.domain

/** A help question proven to be present and non-blank.
  *
  * Parse-don't-validate: [[HelpQuestion.validate]] turns the raw, possibly-absent request field into
  * either an error message or a `HelpQuestion` whose `text` is guaranteed non-blank (and trimmed), so
  * downstream code (the endpoint, the agent) never re-checks it. Mirrors cap-1's
  * `GreetingRequest.validate`/`ValidGreeting`. Pure domain — no Akka dependencies.
  */
final case class HelpQuestion(text: String)

object HelpQuestion:

  /** `Right(HelpQuestion(trimmed))` when a non-blank question is present; otherwise `Left(message)`.
    * `None` (absent field) and blank both fail — no task is started for either.
    */
  def validate(raw: Option[String]): Either[String, HelpQuestion] =
    raw.map(_.trim).filterNot(_.isBlank).map(HelpQuestion.apply).toRight("question must not be blank")
