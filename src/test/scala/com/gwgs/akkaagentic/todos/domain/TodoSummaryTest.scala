package com.gwgs.akkaagentic.todos.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.todos.application.TodoSummaryEntry

/** Pure unit tests for the read-model derivation — **no TestKit, no runtime, no model**.
  *
  * This is the payoff of keeping `TodoSummary.from` a plain function: the arithmetic that every view
  * row depends on is proven here in milliseconds, so the (slower, eventually-consistent) integration
  * tests only have to prove the *wiring*, not the counting. (SC-001, SC-003)
  */
class TodoSummaryTest:

  /** `TodoList.empty` plus the given descriptions, completing the ones whose ids are listed. */
  private def listOf(descriptions: Seq[String], completedIds: Set[Int] = Set.empty): TodoList =
    val added = descriptions.foldLeft(TodoList.empty)((l, d) => l.add(d))
    completedIds.foldLeft(added)((l, id) => l.setCompleted(id, true))

  private def assertInvariant(entry: TodoSummaryEntry): Unit =
    assertThat(entry.openCount + entry.completedCount).isEqualTo(entry.totalCount)

  @Test
  def mixedListCountsOpenAndCompletedSeparately(): Unit =
    // ids are 1-based and assigned in insertion order by TodoList.add
    val entry = TodoSummary.from("alice", listOf(Seq("buy milk", "call dentist", "pay rent"), Set(2)))
    assertThat(entry.username).isEqualTo("alice")
    assertThat(entry.totalCount).isEqualTo(3)
    assertThat(entry.openCount).isEqualTo(2)
    assertThat(entry.completedCount).isEqualTo(1)
    assertInvariant(entry)

  @Test
  def allOpenListHasZeroCompleted(): Unit =
    val entry = TodoSummary.from("bob", listOf(Seq("prepare slides", "book flight")))
    assertThat(entry.totalCount).isEqualTo(2)
    assertThat(entry.openCount).isEqualTo(2)
    assertThat(entry.completedCount).isEqualTo(0)
    assertInvariant(entry)

  @Test
  def allCompletedListHasZeroOpen(): Unit =
    // The state that makes a user disappear from the "who still has open work" query (US2).
    val entry = TodoSummary.from("carol", listOf(Seq("file taxes", "renew passport"), Set(1, 2)))
    assertThat(entry.totalCount).isEqualTo(2)
    assertThat(entry.openCount).isEqualTo(0)
    assertThat(entry.completedCount).isEqualTo(2)
    assertInvariant(entry)

  @Test
  def emptyListProjectsToAllZeroCountsNotNoRow(): Unit =
    // A real row meaning "this assistant has no to-dos" — distinct from there being no row at all.
    val entry = TodoSummary.from("dave", TodoList.empty)
    assertThat(entry.username).isEqualTo("dave")
    assertThat(entry.totalCount).isEqualTo(0)
    assertThat(entry.openCount).isEqualTo(0)
    assertThat(entry.completedCount).isEqualTo(0)
    assertInvariant(entry)

  @Test
  def deletionShrinksTheCountsRatherThanLeavingAStaleTotal(): Unit =
    // The projection is a pure function of the CURRENT list, never a running total: deleting an item
    // must reduce totalCount even though TodoList.nextId (unprojected) stays where it was.
    val entry = TodoSummary.from("erin", listOf(Seq("a", "b", "c"), Set(3)).delete(1))
    assertThat(entry.totalCount).isEqualTo(2)
    assertThat(entry.openCount).isEqualTo(1)
    assertThat(entry.completedCount).isEqualTo(1)
    assertInvariant(entry)
