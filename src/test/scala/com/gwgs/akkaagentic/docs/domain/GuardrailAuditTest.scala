package com.gwgs.akkaagentic.docs.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[GuardrailAudit]] — the identity tag a self-describing rule puts on its
  * explanation, because SDK 3.6.3 gives application code the explanation and nothing else. */
class GuardrailAuditTest:

  @Test
  def aTaggedExplanationRoundTrips(): Unit =
    val tagged = GuardrailAudit.tag("linked answer guard", "HALLUCINATED", "found marker 'http'")
    assertThat(GuardrailAudit.parse(tagged))
      .isEqualTo(("linked answer guard", "HALLUCINATED", "found marker 'http'"))

  /** The SDK's own `SimilarityGuard` cannot tag itself; the block must still stand. */
  @Test
  def anUntaggedExplanationIsReportedWholeWithUnknownIdentity(): Unit =
    val sdkText = "Content similarity [0.83] exceeds threshold [0.75]"
    assertThat(GuardrailAudit.parse(sdkText))
      .isEqualTo((GuardrailAudit.Unknown, GuardrailAudit.Unknown, sdkText))

  @Test
  def bracketsThatAreNotATagAreNotMistakenForOne(): Unit =
    val text = "[not a tag] because it has no slash"
    assertThat(GuardrailAudit.parse(text))
      .isEqualTo((GuardrailAudit.Unknown, GuardrailAudit.Unknown, text))

  @Test
  def aMultiLineExplanationKeepsItsBody(): Unit =
    val tagged = GuardrailAudit.tag("answer length guard", "FORMAT", "5 sentences\nexceeds 2")
    assertThat(GuardrailAudit.parse(tagged)._3).isEqualTo("5 sentences\nexceeds 2")

  @Test
  def anEmptyExplanationIsSafe(): Unit =
    assertThat(GuardrailAudit.parse(""))
      .isEqualTo((GuardrailAudit.Unknown, GuardrailAudit.Unknown, ""))
