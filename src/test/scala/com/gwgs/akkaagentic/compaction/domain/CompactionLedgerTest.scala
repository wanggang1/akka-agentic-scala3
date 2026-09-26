package com.gwgs.akkaagentic.compaction.domain

import java.time.Instant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T011 — the observable record, proven with no runtime.
  *
  * The interesting assertions here are not the counters but the fact that three outcomes stay
  * distinguishable: research R-4 measured that the platform accepts a stale write silently, so a ledger
  * that collapsed `SkippedStale` into `Compacted` would report a bound that was never applied.
  */
class CompactionLedgerTest:

  private val t0 = Instant.parse("2026-09-26T10:00:00Z")
  private def ledger(maxSessions: Int = 10) = CompactionLedger.start(t0, maxSessions)

  @Test
  def aTransitionNeverChangesItsReceiver(): Unit =
    val before = ledger()
    val (after, _) = before.record("alice", 9000, 200, 12, Outcome.Compacted, t0.plusSeconds(1))
    assertThat(before.records.isEmpty).isTrue()
    assertThat(after.records.size).isEqualTo(1)

  @Test
  def compactionsAccumulatePerSessionAndSinceIsPreserved(): Unit =
    val (one, _) = ledger().record("alice", 9000, 200, 12, Outcome.Compacted, t0.plusSeconds(1))
    val (two, record) = one.record("alice", 9100, 210, 14, Outcome.Compacted, t0.plusSeconds(2))
    assertThat(record.compactions).isEqualTo(2)
    assertThat(two.since).isEqualTo(t0)
    assertThat(two.records.size).isEqualTo(1)

  /** A failure or a silently-skipped write is recorded, but it is not a compaction. */
  @Test
  def onlyARealCompactionIncrementsTheCount(): Unit =
    val (one, _) = ledger().record("alice", 9000, 200, 12, Outcome.Compacted, t0.plusSeconds(1))
    val (two, stale) = one.record("alice", 9100, 9100, 0, Outcome.SkippedStale, t0.plusSeconds(2))
    assertThat(stale.compactions).isEqualTo(1)
    assertThat(stale.lastOutcome).isEqualTo(Outcome.SkippedStale)
    val (_, failed) = two.record("alice", 9200, 9200, 0, Outcome.Failed("model timed out"), t0.plusSeconds(3))
    assertThat(failed.compactions).isEqualTo(1)
    assertThat(failed.lastOutcome).isEqualTo(Outcome.Failed("model timed out"))

  @Test
  def theThreeOutcomesAreDistinguishable(): Unit =
    assertThat(Outcome.Compacted).isNotEqualTo(Outcome.SkippedStale)
    assertThat(Outcome.Failed("a")).isNotEqualTo(Outcome.Failed("b"))
    assertThat(Outcome.Failed("a")).isNotEqualTo(Outcome.SkippedStale)

  @Test
  def retentionEvictsTheLeastRecentlyChangedSession(): Unit =
    val (a, _) = ledger(maxSessions = 2).record("alice", 1, 1, 1, Outcome.Compacted, t0.plusSeconds(1))
    val (b, _) = a.record("bob", 1, 1, 1, Outcome.Compacted, t0.plusSeconds(2))
    val (c, _) = b.record("carol", 1, 1, 1, Outcome.Compacted, t0.plusSeconds(3))
    assertThat(c.records.size).isEqualTo(2)
    assertThat(c.get("alice").isEmpty).isTrue()
    assertThat(c.get("bob").isDefined).isTrue()
    assertThat(c.get("carol").isDefined).isTrue()

  /** Two attempts in the same instant must not let the tiebreak evict the record just written — the
    * caller is about to be handed it. */
  @Test
  def theSessionJustRecordedIsNeverTheOneEvicted(): Unit =
    val (a, _) = ledger(maxSessions = 1).record("zzz", 1, 1, 1, Outcome.Compacted, t0)
    val (b, record) = a.record("aaa", 2, 2, 2, Outcome.Compacted, t0)
    assertThat(b.records.size).isEqualTo(1)
    assertThat(b.get("aaa").isDefined).isTrue()
    assertThat(record.lastBytesBefore).isEqualTo(2)

  @Test
  def readsAreOrderedOldestFirstSoAReaderSeesAStableOrder(): Unit =
    val (a, _) = ledger().record("carol", 1, 1, 1, Outcome.Compacted, t0.plusSeconds(3))
    val (b, _) = a.record("alice", 1, 1, 1, Outcome.Compacted, t0.plusSeconds(1))
    val (c, _) = b.record("bob", 1, 1, 1, Outcome.Compacted, t0.plusSeconds(2))
    assertThat(c.all.map(_._1).mkString(",")).isEqualTo("alice,bob,carol")

  @Test
  def anUnknownSessionIsSimplyAbsent(): Unit =
    assertThat(ledger().get("nobody").isEmpty).isTrue()
