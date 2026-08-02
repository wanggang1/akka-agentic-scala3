package com.gwgs.akkaagentic.a2a.domain

/** A request to a personal assistant proven to name a non-blank user and carry a non-blank message.
  *
  * Parse-don't-validate (mirrors cap-3's `HelpQuestion` / cap-5's [[com.gwgs.akkaagentic.approvals.domain.ApprovalQuestion]]):
  * [[AssistantRequest.validate]] turns the raw path segment (`username`) and the optional request-body
  * field (`message`) into either an error message or an `AssistantRequest` whose fields are guaranteed
  * present and trimmed — so the endpoint and agent never re-check them. Pure domain, no Akka deps.
  *
  * The `username` is also the session id (chat history) and the `TodoEntity` id, so it must be present
  * before any component is addressed.
  */
final case class AssistantRequest(username: String, message: String)

object AssistantRequest:

  /** `Right(AssistantRequest(trimmed))` when both a non-blank username and message are present;
    * otherwise the first failing `Left(message)`. `null`/absent and blank both fail — no model call.
    */
  def validate(rawUsername: String, rawMessage: Option[String]): Either[String, AssistantRequest] =
    for
      username <- Option(rawUsername).map(_.trim).filterNot(_.isBlank).toRight("username must not be blank")
      message  <- rawMessage.map(_.trim).filterNot(_.isBlank).toRight("message must not be blank")
    yield AssistantRequest(username, message)
