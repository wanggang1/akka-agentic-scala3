package com.gwgs.akkaagentic.compaction.application

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec

import com.gwgs.akkaagentic.compaction.domain.{CompactionLedger, Outcome, SessionRecord}
import com.typesafe.config.Config

/** The capability's only mutable thing: one cell holding one immutable [[CompactionLedger]].
  *
  * Every change is a pure `CompactionLedger => (CompactionLedger, Result)` committed by compare-and-set,
  * retrying against the winner on a lost race — capability 15's `ReminderStore` pattern, kept because a
  * consumer is constructed per message and shared state therefore has to live in an `object`. That is the
  * reason for it, not a thread-safety guarantee in itself; the guarantee comes from the transitions being
  * pure, which is why they live in the domain and why nothing here holds a `var`.
  *
  * The ledger is **in-process**. A restart empties it while the compacted histories themselves survive in
  * the runtime's entity, so every read carries `since` — the window these numbers can speak for.
  */
object CompactionStore:

  private val cell =
    AtomicReference(CompactionLedger.start(Instant.now(), maxSessions = 1000))

  /** Applies the configured retention. The consumer calls this as it is constructed; it is idempotent for
    * the same value, so per-message construction does not keep resetting the ledger. */
  def configure(config: Config): Unit =
    val maxSessions = CompactionSettings.maxSessions(config)
    cell.updateAndGet(ledger =>
      if ledger.maxSessions == maxSessions then ledger
      else CompactionLedger.start(ledger.since, maxSessions))

  def record(
      sessionId: String,
      bytesBefore: Long,
      bytesAfter: Long,
      messagesReplaced: Int,
      outcome: Outcome,
      at: Instant = Instant.now()
  ): SessionRecord =
    modify(_.record(sessionId, bytesBefore, bytesAfter, messagesReplaced, outcome, at))

  def ledger: CompactionLedger = cell.get()

  @tailrec
  private def modify[A](transition: CompactionLedger => (CompactionLedger, A)): A =
    val current = cell.get()
    val (next, result) = transition(current)
    if cell.compareAndSet(current, next) then result
    else modify(transition)
