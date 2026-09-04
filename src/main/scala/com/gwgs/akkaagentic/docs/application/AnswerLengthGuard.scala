package com.gwgs.akkaagentic.docs.application

import akka.javasdk.agent.{Guardrail, TextGuardrail}
import com.gwgs.akkaagentic.docs.domain.{AnswerRules, GuardrailAudit}

/** Response-side, **record-only**: an answer should stay within a couple of sentences, mirroring the
  * instruction capability 8's system message already gives the model.
  *
  * This is a *style* rule, not a safety one, which is exactly why it is declared `report-only = true`:
  * a verbose answer is worth recording and not worth refusing. Enforcement mode is a configuration
  * key, so the same class demonstrates both governance modes with no second implementation (SC-005).
  *
  * ==Why this class takes no constructor parameters==
  *
  * The runtime tries two constructors in order — `(GuardrailContext)`, then **zero-arg** (research
  * R1). [[LinkedAnswerGuard]] exercises the first; this class exercises the second, which the
  * published documentation does not mention at all. Shipping one of each is what makes R1 a result
  * rather than a half-answer.
  *
  * ==The cost of the no-arg form, which is itself a finding==
  *
  * Without a `GuardrailContext` this rule has **no access to its own configuration section** — so it
  * cannot read a threshold, and cannot even learn the name it was declared under. [[RuleName]] and
  * [[Category]] below are therefore **duplicated** from `application.conf` and must be kept in step
  * with it by hand; there is no mechanism that can check they agree.
  *
  * That is the real trade between the two forms, and it is not the one the docs imply. Take the
  * `(GuardrailContext)` form unless a rule genuinely has nothing to configure — and note that
  * "nothing to configure" still costs you self-identification, because SDK 3.6.3 gives application
  * code no other channel for a rule's name (research divergence #4).
  */
class AnswerLengthGuard extends TextGuardrail:
  import AnswerLengthGuard.*

  override def evaluate(text: String): Guardrail.Result =
    val sentences = AnswerRules.sentenceCount(text)
    if sentences <= MaxSentences then Guardrail.Result.OK
    else
      Guardrail.Result(
        false,
        GuardrailAudit.tag(RuleName, Category, s"Answer is $sentences sentences, over the limit of $MaxSentences")
      )

object AnswerLengthGuard:

  /** Matches `DocsAgent`'s own instruction to answer "concisely in one or two sentences". */
  private val MaxSentences = 2

  /** MUST match the rule's declared name and category in `application.conf`. Duplicated because a
    * zero-arg guardrail is handed no `GuardrailContext` to read them from — see the class scaladoc. */
  private val RuleName = "answer length guard"
  private val Category = "FORMAT"
