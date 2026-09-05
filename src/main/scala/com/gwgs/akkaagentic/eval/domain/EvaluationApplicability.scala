package com.gwgs.akkaagentic.eval.domain

/** Is this subject judgeable at all?
  *
  * Decided **before** any judge is called, so an unjudgeable subject costs zero model calls
  * (research R6). The SDK's built-in evaluator does no validation of its own — an empty reference
  * section, or a guardrail refusal string in place of an answer, both produce a well-formed prompt and
  * a confident, meaningless verdict. Deciding applicability is therefore ours, and it belongs here:
  * pure, **no Akka import** (Constitution II).
  */
object EvaluationApplicability:

  /** Why a subject could not be judged. Constants rather than inline literals so the tests and the
    * verdict explanations cannot drift apart. */
  val RefusedReason: String = "the interaction was refused by a guardrail"
  val NoReferenceReason: String = "no reference material was retrieved"

  enum Applicability:
    case Applicable
    case NotApplicable(reason: String)

  /** Decide, in order: a refused interaction has no answer to judge; absent reference material gives
    * a grounding judgement nothing to judge *against*; anything else is judgeable.
    *
    * `refusalPrefix` is a **parameter, not an import**. Capability 8's `DocsAgent.BlockedPrefix` lives
    * in `application`, and `domain` must not depend on it (research D6) — the caller supplies it.
    *
    * Note what is deliberately **not** here: a *decline* ("I don't know") is `Applicable`. Judging
    * whether a decline was warranted is the entire point of the authored judge (US2); treating a
    * decline as unjudgeable would silently delete half the capability.
    */
  def of(answer: String, referenceText: String, refusalPrefix: String): Applicability =
    if answer != null && answer.startsWith(refusalPrefix) then
      Applicability.NotApplicable(RefusedReason)
    else if ReferenceText.isEmpty(referenceText) then
      Applicability.NotApplicable(NoReferenceReason)
    else Applicability.Applicable
