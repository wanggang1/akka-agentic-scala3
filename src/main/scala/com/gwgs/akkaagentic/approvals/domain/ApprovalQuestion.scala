package com.gwgs.akkaagentic.approvals.domain

/** A customer question proven to be present and non-blank, ready to start an approval case.
  *
  * Parse-don't-validate: [[ApprovalQuestion.validate]] turns the raw, possibly-absent request field into
  * either an error message or an `ApprovalQuestion` whose `text` is guaranteed non-blank (and trimmed),
  * so downstream code (the endpoint, the draft task) never re-checks it. Mirrors cap-3's `HelpQuestion`.
  * Pure domain — no Akka dependencies.
  *
  * The reviewer's decision body carries only an optional free-text note, which needs no validation, so
  * this is the capability's only domain validator.
  */
final case class ApprovalQuestion(text: String)

object ApprovalQuestion:

  /** `Right(ApprovalQuestion(trimmed))` when a non-blank question is present; otherwise `Left(message)`.
    * `None` (absent field) and blank both fail — no case is started and no agent is invoked for either.
    */
  def validate(raw: Option[String]): Either[String, ApprovalQuestion] =
    raw.map(_.trim).filterNot(_.isBlank).map(ApprovalQuestion.apply).toRight("question must not be blank")
