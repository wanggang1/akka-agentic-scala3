package com.gwgs.akkaagentic.feed.domain

import java.time.Instant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T008 — the feed's bookkeeping: sequences, retention, eviction, and the separation of activity from
  * failure. All pure: no runtime, no threads. */
class ActivityFeedTest:

  private val at = Instant.parse("2026-09-25T10:00:00Z")
  private val limits = FeedLimits(maxEntries = 5, maxSetAsides = 2, maxUsers = 2)
  private def feed = ActivityFeed.start(at, limits)
  private def list(items: (Int, String, Boolean)*): TodoSnapshot =
    TodoSnapshot(items.map((id, d, c) => id -> TodoItem(d, c)).toMap, items.size + 1)

  @Test
  def aTransitionNeverChangesTheValueItWasCalledOn(): Unit =
    val (after, recorded) = feed.record("alice", list((1, "buy milk", false)), at)
    assertThat(recorded.size).isEqualTo(1)
    assertThat(feed.entries.isEmpty).isTrue()   // the receiver is untouched
    assertThat(after.entries.size).isEqualTo(1)

  @Test
  def sequencesAreMonotonicAcrossUsers(): Unit =
    val (f1, _) = feed.record("alice", list((1, "a", false)), at)
    val (f2, _) = f1.record("bob", list((1, "b", false)), at)
    val (f3, _) = f2.record("alice", list((1, "a", false), (2, "c", false)), at)
    assertThat(f3.entries.map(_.sequence).mkString(",")).isEqualTo("1,2,3")

  @Test
  def aDuplicateStateRecordsNothing(): Unit =
    val state = list((1, "a", false))
    val (f1, _) = feed.record("alice", state, at)
    val (f2, second) = f1.record("alice", state, at)
    assertThat(second.isEmpty).isTrue()
    assertThat(f2.entries.size).isEqualTo(1)

  @Test
  def retentionDropsTheOldestEntriesAndSetAsides(): Unit =
    val filled = (1 to 8).foldLeft(feed) { (f, i) =>
      f.record("alice", list((1 to i).map(n => (n, s"item$n", false))*), at)._1
    }
    assertThat(filled.entries.size).isEqualTo(limits.maxEntries)
    assertThat(filled.entries.head.sequence).isEqualTo(4L) // 1..3 dropped, newest kept

    val aside = (1 to 3).foldLeft(feed)((f, i) => f.setAside("alice", s"fp$i", 3, "boom", at)._1)
    assertThat(aside.setAsides.size).isEqualTo(limits.maxSetAsides)
    assertThat(aside.setAsides.map(_.fingerprint).mkString(",")).isEqualTo("fp2,fp3")

  @Test
  def theLeastRecentlyChangedUserIsEvictedAndComesBackAsABaseline(): Unit =
    val (f1, _) = feed.record("alice", list((1, "a", false)), at)
    val (f2, _) = f1.record("bob", list((1, "b", false)), at)
    val (f3, _) = f2.record("carol", list((1, "c", false)), at)          // evicts alice (maxUsers = 2)
    assertThat(f3.lastState.keySet.toList.sorted.mkString(",")).isEqualTo("bob,carol")

    val (_, afterEviction) = f3.record("alice", list((1, "a", false)), at)
    assertThat(afterEviction.map(_.change.kind).mkString(",")).isEqualTo("baseline")

  @Test
  def aDeletionIsRecordedEvenForAUserTheFeedNeverSaw(): Unit =
    val (after, entry) = feed.deleted("stranger", at)
    assertThat(entry.change).isEqualTo(TodoChange.ListDeleted)
    assertThat(after.lastState.contains("stranger")).isFalse()

  @Test
  def aDeletionForgetsTheLastStateSoTheNextChangeIsABaseline(): Unit =
    val (f1, _) = feed.record("alice", list((1, "a", false)), at)
    val (f2, _) = f1.deleted("alice", at)
    val (_, next) = f2.record("alice", list((1, "a", false)), at)
    assertThat(next.map(_.change.kind).mkString(",")).isEqualTo("baseline")

  @Test
  def setAsidesAreNeverCountedAsActivity(): Unit =
    val (after, _) = feed.setAside("alice", "fp", 3, "boom", at)
    assertThat(after.entries.isEmpty).isTrue()
    assertThat(after.setAsides.size).isEqualTo(1)

  @Test
  def attemptsAreCountedPerDeliveryAndForgottenOnceSettled(): Unit =
    val key = DeliveryKey("todo-activity-consumer", "alice", "fp-1")
    val other = DeliveryKey("todo-activity-consumer", "alice", "fp-2")
    val (f1, a1) = feed.attempted(key)
    val (f2, a2) = f1.attempted(key)
    val (f3, b1) = f2.attempted(other)
    assertThat(s"$a1,$a2,$b1").isEqualTo("1,2,1")       // a different state counts separately
    assertThat(f3.settled(key).attemptsFor(key)).isEqualTo(0)
    assertThat(f3.settled(key).attemptsFor(other)).isEqualTo(1)
