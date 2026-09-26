package com.gwgs.akkaagentic.compaction.domain

import java.time.Instant

/** How a compaction attempt ended.
  *
  * Three cases rather than a boolean, and the reason is measured (specs/019 research R-4): the platform
  * accepts a `compactHistory` carrying a stale sequence number **silently** — no exception, no result, and
  * the history simply unchanged. So "we asked for a compaction" and "a compaction happened" are different
  * facts, and a record that could not tell them apart would report a bound that was never applied.
  */
enum Outcome:
  case Compacted
  case SkippedStale
  case Failed(reason: String)

/** What is known about one session's compaction history. */
final case class SessionRecord(
    compactions: Int,
    lastBytesBefore: Long,
    lastBytesAfter: Long,
    lastMessagesReplaced: Int,
    lastOutcome: Outcome,
    lastAt: Instant
)

/** Every session this process has tried to compact — one immutable value.
  *
  * Transitions are pure `CompactionLedger => (CompactionLedger, Result)` and never touch the receiver, so
  * the only mutable thing in the capability is the single atomic cell in
  * [[com.gwgs.akkaagentic.compaction.application.CompactionStore]] that holds one of these. That split is
  * the rule capability 15's review established, and it matters here because a compare-and-set retries: a
  * transition has to be pure or a lost race corrupts the count.
  *
  * `since` is the moment this value began recording. It is returned on every read because the ledger is
  * **in-process**: a restart empties it while the compacted histories themselves survive in the runtime's
  * entity, so a reader has to be told which window the numbers cover — the same honesty capabilities 15
  * and 16 ship.
  *
  * Retention is bounded by `maxSessions`, evicting the least-recently-changed session first, so a
  * long-running service cannot grow without limit.
  */
final case class CompactionLedger(
    since: Instant,
    maxSessions: Int,
    records: Map[String, SessionRecord]
):

  /** Records a completed attempt. `bytesAfter` must be what was **read back** from the entity, never a
    * prediction — see [[Outcome]]. */
  def record(
      sessionId: String,
      bytesBefore: Long,
      bytesAfter: Long,
      messagesReplaced: Int,
      outcome: Outcome,
      at: Instant
  ): (CompactionLedger, SessionRecord) =
    val previous = records.get(sessionId)
    val compactions = previous.map(_.compactions).getOrElse(0) + (if outcome == Outcome.Compacted then 1 else 0)
    val updated = SessionRecord(compactions, bytesBefore, bytesAfter, messagesReplaced, outcome, at)
    (copy(records = evict(records.updated(sessionId, updated), keep = sessionId)), updated)

  def get(sessionId: String): Option[SessionRecord] = records.get(sessionId)

  /** Oldest-recorded first, so a reader sees a stable order rather than a hash order. */
  def all: List[(String, SessionRecord)] = records.toList.sortBy { case (id, r) => (r.lastAt, id) }

  /** Least-recently-changed first, and never the session just recorded — two attempts in the same
    * millisecond would otherwise let the tiebreak drop the record the caller is about to be handed. */
  private def evict(candidates: Map[String, SessionRecord], keep: String): Map[String, SessionRecord] =
    if candidates.size <= maxSessions then candidates
    else
      val removable = candidates.toList.filterNot(_._1 == keep).sortBy { case (id, r) => (r.lastAt, id) }
      candidates -- removable.take(candidates.size - maxSessions).map(_._1)

object CompactionLedger:
  def start(since: Instant, maxSessions: Int): CompactionLedger =
    CompactionLedger(since, maxSessions, Map.empty)
