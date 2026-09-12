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
  val FailedReason: String = "the assistant failed to answer"
  val NoReferenceReason: String = "no reference material was retrieved"

  enum Applicability:
    /** Judgeable — and carrying the answer, so the caller judges it without re-checking or
      * unwrapping anything (parse, don't validate). */
    case Applicable(answer: String)
    case NotApplicable(reason: String)

  /** Decide, in order: a turn that produced no answer has nothing to judge, whether it **failed** or
    * was **refused**; absent reference material gives a grounding judgement nothing to judge
    * *against*; anything else is judgeable.
    *
    * `reply` is `None` when the assistant's call itself failed — a failure outside the agent, which
    * its own failure handler never sees. A failure *inside* the agent arrives as a reply behind
    * `failurePrefix` instead. Both are the same fact to a judge: no answer was produced.
    *
    * Both prefixes are **parameters, not imports**. Capability 8's `DocsAgent.BlockedPrefix` and
    * `DocsAgent.FailedPrefix` live in `application`, and `domain` must not depend on it
    * (research D6) — the caller supplies them.
    *
    * Note what is deliberately **not** here: a *decline* ("I don't know") is `Applicable`. Judging
    * whether a decline was warranted is the entire point of the authored judge (US2); treating a
    * decline as unjudgeable would silently delete half the capability. Which is exactly why a failure
    * must never *look* like a decline — it would be judged as one.
    */
  def of(
      reply: Option[String],
      referenceText: String,
      refusalPrefix: String,
      failurePrefix: String
  ): Applicability =
    reply match
      case None                                             => Applicability.NotApplicable(FailedReason)
      case Some(r) if r.startsWith(failurePrefix)           => Applicability.NotApplicable(FailedReason)
      case Some(r) if r.startsWith(refusalPrefix)           => Applicability.NotApplicable(RefusedReason)
      case Some(_) if ReferenceText.isEmpty(referenceText) => Applicability.NotApplicable(NoReferenceReason)
      case Some(answer)                                     => Applicability.Applicable(answer)
