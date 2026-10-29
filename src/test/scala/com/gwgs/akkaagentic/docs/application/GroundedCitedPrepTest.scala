package com.gwgs.akkaagentic.docs.application

import scala.jdk.CollectionConverters.*

import com.gwgs.akkaagentic.docs.domain.{Passage, RetrievedSources}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [[GroundedCitedPrep]] — the Java-callable façade (no runtime). Exercises the honesty
  * floor over the streamed `[n]` report (reusing cap-21's cases) and the retrieval prep, so the forced-Java
  * endpoint's only remaining job is wiring the stream. */
class GroundedCitedPrepTest:

  private def nums(ns: Int*): java.util.List[Integer] = ns.map(Int.box).asJava
  private val noNumbers: java.util.List[Integer] = java.util.List.of()
  private val retrievedLabels: java.util.List[String] = java.util.List.of("a", "b", "c")
  private val sources = RetrievedSources.of(List("a", "b", "c"))

  @Test
  def reportedSubsetIsCitedInRetrievalOrder(): Unit =
    // Model reported using sources 3 and 1 (out of order) → cited subset follows presentation order.
    val event = GroundedCitedPrep.citationsEvent(sources, nums(3, 1), declined = false, retrievedLabels)
    assertThat(event.usedSources()).containsExactly("a", "c")
    assertThat(event.retrievedSources()).containsExactly("a", "b", "c")

  @Test
  def outOfRangeOrNonRetrievedNumbersAreDroppedByTheFloor(): Unit =
    // 0, negative and too-large numbers were never presented — they cannot become a citation (SC-002).
    val event = GroundedCitedPrep.citationsEvent(sources, nums(1, 0, -2, 99), declined = false, retrievedLabels)
    assertThat(event.usedSources()).containsExactly("a")

  @Test
  def aDeclineCitesNothingButStillShowsRetrieved(): Unit =
    // Even if numbers were somehow reported, a decline forces empty citations (FR-007).
    val event = GroundedCitedPrep.citationsEvent(sources, nums(1, 2), declined = true, retrievedLabels)
    assertThat(event.usedSources()).isEmpty()
    assertThat(event.retrievedSources()).containsExactly("a", "b", "c")

  @Test
  def anEmptyOrUnverifiableReportCitesNothingButStillShowsRetrieved(): Unit =
    // Non-decline answer that reported nothing verifiable → cite nothing, never fall back to all retrieved
    // (FR-008). Retrieved list stays populated so the under-report is visible.
    val emptyReport = GroundedCitedPrep.citationsEvent(sources, noNumbers, declined = false, retrievedLabels)
    assertThat(emptyReport.usedSources()).isEmpty()
    assertThat(emptyReport.retrievedSources()).containsExactly("a", "b", "c")

    val nullReport = GroundedCitedPrep.citationsEvent(sources, null, declined = false, retrievedLabels)
    assertThat(nullReport.usedSources()).isEmpty()

  @Test
  def prepareBuildsTheRequestSourcesAndDistinctRetrievedLabels(): Unit =
    // A stub corpus with distinct sources; retrieval is deterministic for a corpus+question (no model).
    val store = new KnowledgeStore(
      List(
        Passage("durability-tasks", "agent work survives a restart because tasks are durable records"),
        Passage("session-memory", "session memory keeps conversational context across turns"),
        Passage("guardrails", "guardrails constrain and control agent behaviour")
      )
    )
    val prepared = GroundedCitedPrep.prepare(store, "what makes agent work survive a restart?", 3)

    // Question propagated verbatim; passages are a subset of the corpus (source+text carried through).
    assertThat(prepared.request.question).isEqualTo("what makes agent work survive a restart?")
    assertThat(prepared.request.passages).hasSize(3)
    assertThat(prepared.retrievedLabels)
      .containsExactlyInAnyOrder("durability-tasks", "session-memory", "guardrails")

    // The presented-source set lines up 1..n with the passages, in the same order.
    val presented = prepared.sources.indexes.map(_.value)
    assertThat(presented.asJava).containsExactly(1, 2, 3)
