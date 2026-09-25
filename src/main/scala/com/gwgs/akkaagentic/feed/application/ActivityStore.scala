package com.gwgs.akkaagentic.feed.application

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec

import com.gwgs.akkaagentic.feed.domain.*
import com.typesafe.config.Config

/** Where the activity feed lives: in this process, for as long as it runs.
  *
  * **The trade-off, stated rather than implied.** The consumer's own position is durable — after a restart
  * it resumes and replays nothing (specs/018 research Q-E) — so this feed cannot be rebuilt from the
  * source. A restart therefore starts a new window, and `since` is returned on every read so a reader can
  * see which window they are looking at. A durable feed would need an entity, whose client is
  * method-reference-only (Java), and is recorded as a fork.
  *
  * **One mutable cell, and nothing else.** Everything it holds is immutable and every change is a pure
  * `ActivityFeed => (ActivityFeed, Result)` committed by compare-and-set, retrying against the winner on a
  * lost race — capability 15's `ReminderStore` pattern. Consumers are constructed per message, so shared
  * state has to live in an object; that is the reason for it, not a thread-safety guarantee in itself.
  */
object ActivityStore:

  private val cell = AtomicReference(ActivityFeed.start(Instant.now(), FeedLimits(500, 100, 1000)))

  /** Applies the configured bounds and restarts the window. The consumer calls this as it is constructed;
    * it is idempotent for the same limits, so per-message construction does not keep resetting the feed. */
  def configure(config: Config): Unit =
    val limits = FeedSettings.limits(config)
    cell.updateAndGet(feed => if feed.limits == limits then feed else ActivityFeed.start(feed.since, limits))

  def record(username: String, snapshot: TodoSnapshot, at: Instant = Instant.now()): List[ActivityEntry] =
    modify(_.record(username, snapshot, at))

  def deleted(username: String, at: Instant = Instant.now()): ActivityEntry =
    modify(_.deleted(username, at))

  def setAside(username: String, fingerprint: String, attempts: Int, reason: String, at: Instant = Instant.now()): SetAside =
    modify(_.setAside(username, fingerprint, attempts, reason, at))

  def attempted(key: DeliveryKey): Int = modify(_.attempted(key))

  def settled(key: DeliveryKey): Unit = cell.updateAndGet(_.settled(key))

  def feed: ActivityFeed = cell.get()

  /** Test hook: the feed outlives individual deliveries by design. */
  def clear(): Unit = cell.updateAndGet(feed => ActivityFeed.start(Instant.now(), feed.limits))

  /** Commit a pure transition: read, compute, compare-and-set; on a lost race redo it against the winner.
    * A transition may therefore run more than once, so it must be pure — which is why timestamps are taken
    * before the loop, never inside it. */
  /** Commit a pure transition: read, compute, compare-and-set; on a lost race redo it against the winner.
    * A transition may therefore run more than once, so it must be pure — which is why timestamps are taken
    * before the loop, never inside it. (No captured `var`: the result comes out of the same computation
    * that commits, the way capability 15's `ReminderStore.modify` does.) */
  @tailrec
  private def modify[B](transition: ActivityFeed => (ActivityFeed, B)): B =
    val current = cell.get()
    val (next, result) = transition(current)
    if cell.compareAndSet(current, next) then result else modify(transition)
