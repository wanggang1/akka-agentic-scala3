package com.gwgs.akkaagentic.domain

/** A greeting request: the user's name and the message text they sent.
  *
  * Pure domain value with no Akka dependencies, so it is trivially unit-testable
  * in isolation. Validation lives on the type (Constitution II).
  */
final case class GreetingRequest(user: String, text: String):

  /** Validate this request.
    *
    * @return
    *   `Right(this)` when both `user` and `text` are present and non-blank,
    *   otherwise `Left(message)` describing the first failing field.
    */
  def validate: Either[String, GreetingRequest] =
    if isBlank(user) then Left("user must not be blank")
    else if isBlank(text) then Left("text must not be blank")
    else Right(this)

  private def isBlank(value: String): Boolean =
    value == null || value.isBlank

/** The personalized greeting produced for a [[GreetingRequest]]. */
final case class GreetingResponse(greeting: String)
