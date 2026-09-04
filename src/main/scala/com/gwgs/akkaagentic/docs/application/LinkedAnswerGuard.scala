package com.gwgs.akkaagentic.docs.application

import scala.jdk.CollectionConverters.*
import scala.util.Try

import akka.javasdk.agent.{Guardrail, GuardrailContext, TextGuardrail}
import com.gwgs.akkaagentic.docs.domain.{AnswerRules, GuardrailAudit}

/** Response-side, enforcing: **an answer must not direct the reader to an external web source.**
  *
  * The corpus contains no links, so a link in the answer is evidence the model reached outside its
  * supplied passages. That makes this a **proxy** for ungroundedness, not proof of it — a
  * `TextGuardrail` receives the answer text and nothing else (no question, no passages), so a true
  * grounding check is out of reach on this interface (spec Assumptions). The proxy is sound *here*
  * precisely because of a property of this corpus, and would not transfer unexamined to another one.
  *
  * ==Why this class is shaped exactly like this==
  *
  * It is a **top-level Scala `class`** with a **single, plain** `(ctx: GuardrailContext)` parameter
  * list. Every part of that is load-bearing. The runtime constructs a guardrail from a class-name
  * string via `DynamicAccess.createInstanceFor`, which matches a constructor by **exact declared
  * parameter types** — first `(GuardrailContext)`, then zero-arg (research R1). So:
  *
  *   - a curried or `using`/implicit parameter list changes the erased signature and would not match;
  *   - a companion `object` compiles to a private constructor reachable only through `MODULE$` and
  *     matches neither attempt — shipped as a deliberate negative probe in the test sources;
  *   - a `case class` would work but adds `apply`/`unapply`/equality nothing uses.
  *
  * This is the **bytecode-shape** hazard class, not the method-reference wall: the SDK reflects on
  * this class rather than dispatching to it, the same question capability 11 hit with its `TableUpdater`
  * and reached through a completely different mechanism.
  *
  * The rule *logic* is not here — it is pure, Akka-free and unit-tested in
  * [[com.gwgs.akkaagentic.docs.domain.AnswerRules]]. This class is the adapter: read settings, call the
  * predicate, shape the verdict.
  */
class LinkedAnswerGuard(ctx: GuardrailContext) extends TextGuardrail:

  /** Substrings that indicate an external reference, from the rule's **own** config section — so the
    * rule is retuned by configuration, never by recompiling (FR-007). */
  private val markers: List[String] =
    Try(ctx.config.getStringList("link-markers").asScala.toList)
      .getOrElse(LinkedAnswerGuard.DefaultMarkers)

  /** The declared category, read back from the same section so the tag below cannot contradict the
    * configuration that produced it. */
  private val category: String =
    Try(ctx.config.getString("category")).getOrElse(LinkedAnswerGuard.DefaultCategory)

  override def evaluate(text: String): Guardrail.Result =
    AnswerRules.firstExternalReferenceMarker(text, markers) match
      case None => Guardrail.Result.OK
      case Some(marker) =>
        // Self-tagged: SDK 3.6.3 hands application code the explanation and nothing else, so a rule
        // that wants to be named in the caller-facing 422 has to name itself (research divergence #4).
        Guardrail.Result(
          false,
          GuardrailAudit.tag(ctx.name, category, s"Answer contains an external reference marker '$marker'")
        )

object LinkedAnswerGuard:
  /** Used only if the declaration omits `link-markers`; the shipped configuration supplies them. */
  private val DefaultMarkers: List[String] = List("http", "www.")
  private val DefaultCategory: String = "HALLUCINATED"
