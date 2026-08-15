package com.gwgs.akkaagentic.docs.domain

/** A validated, non-blank question. Parse-don't-validate: constructing an [[AskQuestion]] proves the
  * question is present and trimmed, so downstream code (retrieval, agent) never re-checks.
  */
final case class AskQuestion(question: String)

object AskQuestion:

  /** Validate an optional inbound question. Absent, blank, or whitespace-only input is rejected so the
    * endpoint returns 400 before any retrieval or model call (FR-006); a present value is trimmed.
    */
  def validate(question: Option[String]): Either[String, AskQuestion] =
    question
      .map(_.trim)
      .filterNot(_.isBlank)
      .map(AskQuestion(_))
      .toRight("question must not be blank")
