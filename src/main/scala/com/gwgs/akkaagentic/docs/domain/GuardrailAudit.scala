package com.gwgs.akkaagentic.docs.domain

/** A tiny encoding that lets a guardrail's explanation carry the rule's own identity.
  *
  * It exists because of a measured limitation of Akka SDK 3.6.3: when a guardrail blocks, the only
  * thing application code ever receives is the rule's **explanation string**. The runtime does compose
  * a full audit line — `Request guardrail blocked, category [FORMAT], name [probe always fail]: …` —
  * but that lives on the SPI-internal `AgentException` and goes to logs and traces; the public
  * `Guardrail.GuardrailException` handed to an agent's `onFailure` is constructed from the bare
  * explanation, with no name, no category and no cause (research R3-RESOLVED, divergence #4).
  *
  * So a rule that wants to be *named* in the caller-facing `422` has to say so itself. Rules we author
  * prefix their explanation with `[name/CATEGORY] `; rules we don't author — notably the SDK's own
  * `SimilarityGuard` — cannot, and are reported as `unknown`. That asymmetry is deliberate and is
  * itself the finding: it is honest about what the platform can and cannot tell us.
  *
  * Pure and Akka-free (Constitution II), so both the guardrail adapters that write the tag and the
  * endpoint that reads it depend on one definition and cannot drift apart.
  */
object GuardrailAudit:

  /** Reported for a rule that did not tag itself. */
  val Unknown: String = "unknown"

  private val Tagged = raw"(?s)\[([^/\]]+)/([^/\]]+)\]\s*(.*)".r

  /** Prefix `explanation` with the rule's identity. */
  def tag(name: String, category: String, explanation: String): String =
    s"[$name/$category] $explanation"

  /** Split a possibly-tagged explanation into `(rule, category, explanation)`.
    *
    * An untagged string is returned whole as the explanation with both identifiers [[Unknown]] — the
    * `422` still stands, because enforcement must never depend on parsing succeeding.
    */
  def parse(explanation: String): (String, String, String) =
    explanation match
      case Tagged(name, category, rest) if !name.isBlank && !category.isBlank =>
        (name.trim, category.trim, if rest.isBlank then explanation else rest)
      case _ =>
        (Unknown, Unknown, explanation)
