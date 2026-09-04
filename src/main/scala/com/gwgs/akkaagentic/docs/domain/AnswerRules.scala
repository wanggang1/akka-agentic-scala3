package com.gwgs.akkaagentic.docs.domain

/** Pure predicates behind capability 12's two response-side guardrails.
  *
  * Deliberately free of any Akka import (Constitution II): a `TextGuardrail` is an SDK interface and
  * so must live in `application`, but *what makes an answer unacceptable* is domain logic and is
  * unit-testable with no runtime, no configuration and no model — the same split as
  * [[AskQuestion]]`.validate` versus `DocsEndpoint`.
  *
  * Both functions take only text, because that is all `TextGuardrail.evaluate` receives: no question,
  * no retrieved passages, no session. That constraint is why capability 12 ships a *proxy* for
  * ungroundedness rather than a true grounding check (spec Assumptions).
  */
object AnswerRules:

  /** The first configured marker that appears in `text`, or `None` if none do.
    *
    * Returns the marker rather than a bare `Boolean` because the guardrail's explanation has to name
    * what it found (FR-003) — a `Boolean` would discard exactly the information the audit line needs.
    *
    * Matching is case-insensitive. Blank markers are ignored rather than matching every string, so a
    * stray empty entry in configuration cannot silently block every answer.
    */
  def firstExternalReferenceMarker(text: String, markers: List[String]): Option[String] =
    val haystack = text.toLowerCase
    markers.iterator
      .filterNot(_.isBlank)
      .map(_.toLowerCase)
      .find(haystack.contains)

  /** How many sentences `text` contains, counting `.`, `!` and `?` as terminators.
    *
    * Blank text is zero sentences; text with no terminator at all is one. Runs of terminators
    * (`"Really?!"`, `"Yes..."`) count once, and a trailing terminator does not add an empty sentence.
    *
    * Deliberately naive: a decimal point reads as a terminator (`"0.75"` counts as two). The only
    * rule built on this count is **record-only**, so an over-count is recorded and never blocks — see
    * `AnswerRulesTest.aDecimalNumberIsMiscountedAndThatIsAcceptedHere`.
    */
  def sentenceCount(text: String): Int =
    text.split("[.!?]+").count(!_.isBlank)
