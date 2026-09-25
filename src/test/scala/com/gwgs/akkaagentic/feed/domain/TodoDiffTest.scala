package com.gwgs.akkaagentic.feed.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T007 — the reaction's whole decision, tested with no runtime: given two states of a list, what happened?
  *
  * The source hands over **state, not events** (research Q-B), so everything the feed reports is decided
  * here. The cases below are the ones the spec commits to (FR-004) and the ones it deliberately does not.
  */
class TodoDiffTest:

  private def snapshot(nextId: Int)(items: (Int, String, Boolean)*): TodoSnapshot =
    TodoSnapshot(items.map((id, d, c) => id -> TodoItem(d, c)).toMap, nextId)

  private def kinds(changes: List[TodoChange]) = changes.map(_.kind).mkString(",")

  @Test
  def theFirstSightingIsABaselineNotARunOfAdditions(): Unit =
    // A consumer can start long after items exist, and a key-value source never replays history. Reporting
    // three pre-existing items as "added" would be a claim the feed cannot support.
    val current = snapshot(4)((1, "buy milk", true), (2, "call mum", false), (3, "pay rent", false))
    val changes = TodoDiff.between(None, current)
    assertThat(kinds(changes)).isEqualTo("baseline")
    assertThat(changes.head).isEqualTo(TodoChange.Baseline(open = 2, completed = 1))

  @Test
  def anIdenticalStateProducesNothing(): Unit =
    // The idempotence rule (D5): a duplicate delivery IS an identical state.
    val s = snapshot(2)((1, "buy milk", false))
    // (a Scala List resolves to AssertJ's ObjectAssert, which has no isEmpty — assert the boolean)
    assertThat(TodoDiff.between(Some(s), s).isEmpty).isTrue()

  @Test
  def eachKindOfChangeIsRecognised(): Unit =
    val before = snapshot(3)((1, "buy milk", false), (2, "call mum", true))
    val after = snapshot(4)((1, "buy milk", true), (3, "pay rent", false))
    // item 1 completed, item 2 removed, item 3 added — all in one delivery
    assertThat(kinds(TodoDiff.between(Some(before), after))).isEqualTo("completed,removed,added")

  @Test
  def completingAndReopeningAreDistinct(): Unit =
    val open = snapshot(2)((1, "buy milk", false))
    val done = snapshot(2)((1, "buy milk", true))
    assertThat(kinds(TodoDiff.between(Some(open), done))).isEqualTo("completed")
    assertThat(kinds(TodoDiff.between(Some(done), open))).isEqualTo("reopened")

  @Test
  def anItemAddedAndRemovedBetweenDeliveriesLeavesNoTrace(): Unit =
    // Not a defect: the platform may fold changes together, so the feed is an activity record and never
    // claims to be a complete history (spec FR-004).
    val before = snapshot(2)((1, "buy milk", false))
    val after = snapshot(3)((1, "buy milk", false)) // item 2 came and went; only nextId moved
    assertThat(TodoDiff.between(Some(before), after).isEmpty).isTrue()

  @Test
  def changesAreOrderedByItemIdSoTheResultIsDeterministic(): Unit =
    val before = snapshot(4)((1, "a", false), (2, "b", false), (3, "c", false))
    val after = snapshot(5)((1, "a", true), (3, "c", true), (4, "d", false))
    assertThat(TodoDiff.between(Some(before), after).flatMap(_.itemIdOpt).mkString(",")).isEqualTo("1,2,3,4")

  @Test
  def theFingerprintDistinguishesStatesThatLookAlike(): Unit =
    // A deletion leaves nextId high, which is what keeps "one item, never deleted" and "one item, after a
    // deletion" different states rather than the same one.
    assertThat(snapshot(2)((1, "x", false)).fingerprint).isNotEqualTo(snapshot(3)((1, "x", false)).fingerprint)
    assertThat(snapshot(2)((1, "x", false)).fingerprint).isEqualTo(snapshot(2)((1, "x", false)).fingerprint)
