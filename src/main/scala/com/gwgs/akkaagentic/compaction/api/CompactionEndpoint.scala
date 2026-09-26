package com.gwgs.akkaagentic.compaction.api

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{Get, HttpEndpoint}
import akka.javasdk.http.HttpResponses
import com.gwgs.akkaagentic.compaction.application.CompactionStore
import com.gwgs.akkaagentic.compaction.domain.{Outcome, SessionRecord}

/** Reading what has been compacted. **`GET`-only by design**: compaction is a property of the memory, not
  * an operation a caller performs, and a manual trigger would be a second code path to keep correct.
  *
  * Scala, like the rest of this capability except the one class holding its method references — an HTTP
  * endpoint has no `ComponentClient` method reference to author, and this one does not even have a client:
  * it reads an in-process ledger.
  *
  * Every response carries **`since`**, because that ledger is in-process while the compacted histories
  * themselves survive in the runtime's entity. A reader is told which window the numbers cover rather than
  * left to assume they cover everything.
  */
@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class CompactionEndpoint:
  import CompactionEndpoint.*

  /** Every session this process has tried to compact. An empty list is a `200`: "nothing has needed
    * compacting" is a complete answer, not a missing resource. */
  @Get("/compaction")
  def all(): HttpResponse =
    val ledger = CompactionStore.ledger
    HttpResponses.ok(
      CompactionsView(
        ledger.since.toString,
        ledger.all.map { case (sessionId, record) => SessionView.of(sessionId, record) }))

  /** One session.
    *
    * A `404` deliberately does **not** distinguish "never needed compacting" from "lost to a restart":
    * the entity keeps no record of who compacted it, so this capability cannot tell. `since` on the
    * collection is how a reader works out which it might be.
    */
  @Get("/compaction/{sessionId}")
  def forSession(sessionId: String): HttpResponse =
    if sessionId == null || sessionId.trim.isEmpty then
      HttpResponses.badRequest("sessionId must not be blank")
    else
      CompactionStore.ledger
        .get(sessionId)
        .map(record => HttpResponses.ok(SessionView.of(sessionId, record)))
        .getOrElse(HttpResponses.notFound(s"no compaction recorded for session [$sessionId]"))

object CompactionEndpoint:

  /** The endpoint's own response types — a domain `SessionRecord` never reaches the wire. */
  final case class SessionView(
      sessionId: String,
      compactions: Int,
      lastBytesBefore: Long,
      lastBytesAfter: Long,
      lastMessagesReplaced: Int,
      lastOutcome: String,
      lastAt: String
  )

  final case class CompactionsView(since: String, sessions: List[SessionView])

  object SessionView:
    def of(sessionId: String, record: SessionRecord): SessionView =
      SessionView(
        sessionId,
        record.compactions,
        record.lastBytesBefore,
        record.lastBytesAfter,
        record.lastMessagesReplaced,
        label(record.lastOutcome),
        record.lastAt.toString)

    /** Three outcomes stay three strings on the wire. Collapsing `skipped-stale` into a success would
      * report a bound that was never applied (research R-4). */
    private def label(outcome: Outcome): String = outcome match
      case Outcome.Compacted    => "compacted"
      case Outcome.SkippedStale => "skipped-stale"
      case Outcome.Failed(_)    => "failed"
