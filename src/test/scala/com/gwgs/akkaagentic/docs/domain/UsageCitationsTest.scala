package com.gwgs.akkaagentic.docs.domain

import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[UsageCitations.select]] — the citation-honesty rule (pure domain, no runtime). */
class UsageCitationsTest:

  private val retrieved = List("durability-tasks", "cap-3-help-desk", "cap-4-session-memory")

  @Test
  def citesOnlyTheReportedSubsetInRetrievalOrder(): Unit =
    // Model used two of the three; reported out of order — result follows retrieval order.
    val result = UsageCitations.select(
      reportedUsed = List("cap-4-session-memory", "durability-tasks"),
      retrievedLabels = retrieved,
      declined = false
    )
    assertThat(result.asJava).containsExactly("durability-tasks", "cap-4-session-memory")

  @Test
  def aSingleUsedSourceYieldsAStrictSubset(): Unit =
    val result = UsageCitations.select(List("durability-tasks"), retrieved, declined = false)
    assertThat(result.asJava).containsExactly("durability-tasks")
    assertThat(result.size).isLessThan(retrieved.size) // strict subset — the point of B4 (SC-001)

  @Test
  def everyCitedLabelWasRetrieved_nonRetrievedReportsAreDropped(): Unit =
    // Self-report names a label that was never offered as context — it must not be citable (FR-004).
    val result = UsageCitations.select(
      reportedUsed = List("durability-tasks", "totally-made-up-label"),
      retrievedLabels = retrieved,
      declined = false
    )
    assertThat(result.asJava).containsExactly("durability-tasks")
    assertThat(retrieved.asJava).containsAll(result.asJava) // invariant: result ⊆ retrieved (SC-002)

  @Test
  def reportedLabelsAreDeduplicated(): Unit =
    val result =
      UsageCitations.select(List("durability-tasks", "durability-tasks"), retrieved, declined = false)
    assertThat(result.asJava).containsExactly("durability-tasks")

  @Test
  def declineCitesNothingEvenWhenSourcesAreReported(): Unit =
    // A decline overrides any reported usage (FR-005).
    val result = UsageCitations.select(List("durability-tasks"), retrieved, declined = true)
    assertThat(result.asJava).isEmpty()

  @Test
  def nonDeclineWithNoUsableReportsCitesNothing(): Unit =
    // Answer given, but the model reported nothing / only non-retrieved labels: cite nothing rather
    // than fall back to the full retrieved set (FR-007, the resolved spec clarification).
    assertThat(UsageCitations.select(Nil, retrieved, declined = false).asJava).isEmpty()
    assertThat(
      UsageCitations.select(List("only-non-retrieved"), retrieved, declined = false).asJava
    ).isEmpty()
