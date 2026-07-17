package com.gwgs.akkaagentic.chat.domain

/** A user chat message proven to be present and non-blank.
  *
  * Parse-don't-validate: [[ChatMessage.validate]] turns the raw, possibly-absent request field into
  * either an error message or a `ChatMessage` whose `text` is guaranteed non-blank (and trimmed), so
  * downstream code (the endpoint, the agent) never re-checks it. Mirrors cap-3's `HelpQuestion`. Pure
  * domain — no Akka dependencies.
  */
final case class ChatMessage(text: String)

object ChatMessage:

  /** `Right(ChatMessage(trimmed))` when a non-blank message is present; otherwise `Left(message)`.
    * `None` (absent field) and blank both fail — the assistant is never engaged for either.
    */
  def validate(raw: Option[String]): Either[String, ChatMessage] =
    raw.map(_.trim).filterNot(_.isBlank).map(ChatMessage.apply).toRight("message must not be blank")
