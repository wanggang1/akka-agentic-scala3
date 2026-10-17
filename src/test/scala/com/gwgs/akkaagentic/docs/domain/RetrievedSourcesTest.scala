package com.gwgs.akkaagentic.docs.domain

import scala.jdk.CollectionConverters.*

import com.gwgs.akkaagentic.docs.domain.RetrievedSources.{Label, SourceIndex}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[RetrievedSources]] — the index→label citation-honesty rule (pure domain, no runtime).
  * The model now reports 1-based source *numbers*; these fix the mapping and the honesty floor. */
class RetrievedSourcesTest:

  private val sources = RetrievedSources.of(List("durability-tasks", "cap-3-help-desk", "cap-4-session-memory"))

  // Convenience: build reported indices and unwrap cited labels for assertions.
  private def idx(ns: Int*): List[SourceIndex] = ns.map(SourceIndex(_)).toList
  private def values(labels: List[Label]): java.util.List[String] = labels.map(_.value).asJava

  @Test
  def exposesTheOneBasedNumbering(): Unit =
    assertThat(sources.indexes.map(_.value).asJava).containsExactly(1, 2, 3)

  @Test
  def citesOnlyTheReportedSubsetInRetrievalOrder(): Unit =
    // Model used sources 3 and 1; reported out of order — result follows retrieval (presentation) order.
    val result = sources.cite(idx(3, 1), declined = false)
    assertThat(values(result)).containsExactly("durability-tasks", "cap-4-session-memory")

  @Test
  def aSingleUsedSourceYieldsAStrictSubset(): Unit =
    val result = sources.cite(idx(1), declined = false)
    assertThat(values(result)).containsExactly("durability-tasks")
    assertThat(result.size).isLessThan(3) // strict subset — the point of B4 (SC-001)

  @Test
  def outOfRangeIndexesAreDropped(): Unit =
    // Self-report names numbers that were never presented — they must not be citable (FR-004).
    // Honesty floor is structural: 0, a negative, and a too-large index all resolve to nothing.
    val result = sources.cite(idx(1, 0, -2, 99), declined = false)
    assertThat(values(result)).containsExactly("durability-tasks")

  @Test
  def reportedIndexesAreDeduplicatedInOutput(): Unit =
    val result = sources.cite(idx(1, 1), declined = false)
    assertThat(values(result)).containsExactly("durability-tasks")

  @Test
  def declineCitesNothingEvenWhenIndexesAreReported(): Unit =
    // A decline overrides any reported usage (FR-005).
    assertThat(values(sources.cite(idx(1), declined = true))).isEmpty()

  @Test
  def nonDeclineWithNoUsableReportsCitesNothing(): Unit =
    // Answer given, but the model reported nothing / only out-of-range numbers: cite nothing rather
    // than fall back to the full retrieved set (FR-007).
    assertThat(values(sources.cite(Nil, declined = false))).isEmpty()
    assertThat(values(sources.cite(idx(99), declined = false))).isEmpty()

  @Test
  def label_resolvesPresentedIndexesAndOnlyThose(): Unit =
    assertThat(sources.label(SourceIndex(2)).map(_.value)).isEqualTo(Some("cap-3-help-desk"))
    assertThat(sources.label(SourceIndex(0)).isEmpty).isTrue()
    assertThat(sources.label(SourceIndex(4)).isEmpty).isTrue()

  @Test
  def duplicateSourceAcrossPassagesOccupiesTwoIndexes(): Unit =
    // Two passages, same source — index n must line up with the n-th presented passage (no dedup in `of`),
    // and a report of either number cites that source once.
    val dup = RetrievedSources.of(List("same-doc", "other", "same-doc"))
    assertThat(dup.label(SourceIndex(1)).map(_.value)).isEqualTo(Some("same-doc"))
    assertThat(dup.label(SourceIndex(3)).map(_.value)).isEqualTo(Some("same-doc"))
    assertThat(values(dup.cite(idx(1, 3), declined = false))).containsExactly("same-doc")
