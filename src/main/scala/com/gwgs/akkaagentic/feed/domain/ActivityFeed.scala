package com.gwgs.akkaagentic.feed.domain

import java.time.Instant

/** One recorded change. */
final case class ActivityEntry(sequence: Long, username: String, change: TodoChange, recordedAt: Instant)

/** A delivery that could not be processed within its permitted attempts. Never counted as activity, never
  * published — a reader must be able to tell "something happened" from "something failed". */
final case class SetAside(username: String, fingerprint: String, attempts: Int, reason: String, recordedAt: Instant)

/** Identifies one delivery for the attempt bound: which consumer, whose list, and which exact state.
  * Content-keyed because nothing else is stable — see `TodoSnapshot.fingerprint`. */
final case class DeliveryKey(consumer: String, username: String, fingerprint: String)

/** How much the feed keeps. Bounded from the start: capability 15 shipped an in-process store that never
  * evicted, and had to disclose it in review (README §17). */
final case class FeedLimits(maxEntries: Int, maxSetAsides: Int, maxUsers: Int)

/** Everything the feed knows, as **one immutable value**.
  *
  * Every operation returns `(ActivityFeed, result)` and never changes the receiver, so the application
  * layer can commit any of them with a single compare-and-set and retry safely (capability 15's pattern).
  *
  * `since` is the moment this value began recording. It is returned on every read because the feed is
  * in-process while the consumer's position is **durable**: after a restart the consumer resumes and does
  * not resend what it already delivered (specs/018 research Q-E), so the feed covers a window, and saying
  * which one is the difference between a limit and a lie.
  */
final case class ActivityFeed(
    since: Instant,
    limits: FeedLimits,
    entries: Vector[ActivityEntry] = Vector.empty,
    setAsides: Vector[SetAside] = Vector.empty,
    lastState: Map[String, TodoSnapshot] = Map.empty,
    recency: Vector[String] = Vector.empty,
    attempts: Map[DeliveryKey, Int] = Map.empty,
    nextSequence: Long = 1L):

  def entriesFor(username: String): Vector[ActivityEntry] = entries.filter(_.username == username)

  /** Record a delivered state. Returns the entries it produced — **empty** when the state is identical to
    * the last one seen for that user, which is what makes a duplicate delivery a no-op (D5). */
  def record(username: String, snapshot: TodoSnapshot, at: Instant): (ActivityFeed, List[ActivityEntry]) =
    TodoDiff.between(lastState.get(username), snapshot) match
      case Nil => (this, Nil)
      case changes =>
        val (feed, recorded) = append(username, changes, at)
        (feed.remember(username, snapshot), recorded)

  /** The whole list was deleted at the source. Recorded even for a user the feed never saw — a removal is
    * not something to swallow — and the user's last state is forgotten. */
  def deleted(username: String, at: Instant): (ActivityFeed, ActivityEntry) =
    val (feed, recorded) = append(username, List(TodoChange.ListDeleted), at)
    (feed.forget(username), recorded.head)

  def setAside(username: String, fingerprint: String, attempts: Int, reason: String, at: Instant): (ActivityFeed, SetAside) =
    val record = SetAside(username, fingerprint, attempts, reason, at)
    (copy(setAsides = (setAsides :+ record).takeRight(limits.maxSetAsides)), record)

  /** Count one attempt at a delivery, returning the attempt number (1 for the first). */
  def attempted(key: DeliveryKey): (ActivityFeed, Int) =
    val attempt = attempts.getOrElse(key, 0) + 1
    (copy(attempts = attempts.updated(key, attempt)), attempt)

  /** Forget a delivery's attempts — once it succeeded or was set aside, its count is spent. Keeps the map
    * bounded by what is actually in flight rather than by history. */
  def settled(key: DeliveryKey): ActivityFeed = copy(attempts = attempts - key)

  def attemptsFor(key: DeliveryKey): Int = attempts.getOrElse(key, 0)

  private def append(username: String, changes: List[TodoChange], at: Instant): (ActivityFeed, List[ActivityEntry]) =
    val recorded = changes.zipWithIndex.map((change, i) => ActivityEntry(nextSequence + i, username, change, at))
    val kept = (entries ++ recorded).takeRight(limits.maxEntries)
    (copy(entries = kept, nextSequence = nextSequence + recorded.size), recorded)

  /** Remember this user's state as the basis for the next comparison, evicting the least recently changed
    * user when the bound is reached. An evicted user's next change is recorded as a `Baseline` — the feed
    * says "this is where I started looking again" rather than inventing a burst of additions. */
  private def remember(username: String, snapshot: TodoSnapshot): ActivityFeed =
    val order = recency.filterNot(_ == username) :+ username
    val evicted = order.dropRight(limits.maxUsers)
    copy(lastState = (lastState -- evicted).updated(username, snapshot), recency = order.takeRight(limits.maxUsers))

  private def forget(username: String): ActivityFeed =
    copy(lastState = lastState - username, recency = recency.filterNot(_ == username))

object ActivityFeed:
  def start(since: Instant, limits: FeedLimits): ActivityFeed = ActivityFeed(since, limits)
