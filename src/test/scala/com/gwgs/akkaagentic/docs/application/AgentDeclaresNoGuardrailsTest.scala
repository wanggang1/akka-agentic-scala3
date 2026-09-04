package com.gwgs.akkaagentic.docs.application

import java.nio.file.{Files, Path}

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** SC-006 / FR-006, asserted **mechanically against the source file** rather than by reading it.
  *
  * The claim capability 12 makes is that governance is *declared*, not *wired*: three rules apply to
  * `docs-agent`, and the agent names none of them. A prose claim like that decays silently the first
  * time someone "just adds a quick check" to the agent — so it is a test, and it fails if the agent
  * ever learns a rule's identity.
  */
class AgentDeclaresNoGuardrailsTest:

  private val agentSource: String =
    Files.readString(Path.of("src/main/scala/com/gwgs/akkaagentic/docs/application/DocsAgent.scala"))

  /** Every string that would mean the agent knows *which* rules govern it. */
  private val ForbiddenInTheAgent = List(
    // rule names, exactly as declared in application.conf
    "default jailbreak",
    "linked answer guard",
    "answer length guard",
    // categories
    "JAILBREAK",
    "HALLUCINATED",
    // guardrail implementation classes
    "SimilarityGuard",
    "LinkedAnswerGuard",
    "AnswerLengthGuard",
    "TextGuardrail",
    // the configuration path that attaches them
    "akka.javasdk.agent.guardrails",
    // the attachment key itself
    "agents ="
  )

  @Test
  def theGuardedAgentNamesNoRuleCategoryOrRuleClass(): Unit =
    // Collected rather than asserted one by one, so a failure names every leak at once.
    val leaked = ForbiddenInTheAgent.filter(agentSource.contains)
    assertThat(leaked.mkString(", ")).isEqualTo("")

  /** The one permitted occurrence, pinned deliberately.
    *
    * `DocsAgent` does import `akka.javasdk.agent.Guardrail`, for exactly one purpose: recognising a
    * `GuardrailException` in `onFailure` so a block is not swallowed into the honest-decline sentinel
    * (FR-005). That is a reference to the *mechanism*, not to any rule — research R8 anticipated
    * precisely this distinction — and it is what lets the agent stay ignorant of which rules exist
    * while still not lying to the caller about why it refused.
    *
    * Asserted as an upper bound so the exception cannot quietly grow into rule-specific logic.
    */
  @Test
  def theOnlyGuardrailReferenceIsTheExceptionType(): Unit =
    val codeLines = agentSource.linesIterator
      .map(_.trim)
      .filterNot(l => l.startsWith("*") || l.startsWith("/*") || l.startsWith("//"))
      .filter(_.contains("Guardrail"))
      .toList

    // Two, and only two: the import, and the `case block: Guardrail.GuardrailException` match.
    // Asserting the lines themselves (not just a count) means a failure shows what crept in.
    assertThat(codeLines.mkString(" | ")).isEqualTo(
      "import akka.javasdk.agent.{Agent, Guardrail} | case block: Guardrail.GuardrailException =>"
    )
