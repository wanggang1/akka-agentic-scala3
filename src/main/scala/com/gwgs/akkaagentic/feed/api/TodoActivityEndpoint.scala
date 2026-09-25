package com.gwgs.akkaagentic.feed.api

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{Get, HttpEndpoint}
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonInclude
import com.gwgs.akkaagentic.feed.application.ActivityStore
import com.gwgs.akkaagentic.feed.domain.{ActivityEntry, SetAside, TodoChange}

/** Reading the activity feed. **Scala**, like everything else in this capability — there is no method
  * reference anywhere in the Consumer family, so unlike capability 11's View over the same entity, the
  * reading side is not forced into Java.
  *
  * Every response carries **`since`**: the feed is in process, while the consumer's position is durable and
  * replays nothing after a restart (specs/018 research Q-E). So a reader is always told which window they
  * are looking at, rather than being left to assume it covers everything.
  */
@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class TodoActivityEndpoint:
  import TodoActivityEndpoint.*

  /** The whole feed, oldest first. An empty feed is a `200`: "nothing has happened" is an answer. */
  @Get("/todo-activity")
  def all(): HttpResponse =
    val feed = ActivityStore.feed
    HttpResponses.ok(FeedView(feed.since.toString, feed.entries.map(EntryView.of).toList))

  /** One user's entries. An unknown user is `200` with none, not `404` — the feed only knows who has done
    * something since `since`, and absence of activity is not absence of a user. */
  @Get("/todo-activity/{username}")
  def forUser(username: String): HttpResponse =
    val feed = ActivityStore.feed
    HttpResponses.ok(FeedView(feed.since.toString, feed.entriesFor(username).map(EntryView.of).toList))

  /** Deliveries that could not be processed, with the reason. Never counted as activity. */
  @Get("/todo-activity/set-aside")
  def setAside(): HttpResponse =
    val feed = ActivityStore.feed
    HttpResponses.ok(SetAsideView.wrap(feed.since.toString, feed.setAsides.map(SetAsideView.of).toList))

object TodoActivityEndpoint:

  /** `NON_ABSENT` omits the fields that do not apply to a given kind, rather than sending nulls a reader
    * would have to interpret. HTTP bodies, so they go through the Scala-aware mapper (README §3). */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class EntryView(
      sequence: Long,
      username: String,
      kind: String,
      recordedAt: String,
      itemId: Option[Int] = None,
      description: Option[String] = None,
      open: Option[Int] = None,
      completed: Option[Int] = None)

  object EntryView:
    def of(entry: ActivityEntry): EntryView =
      val base = EntryView(
        sequence = entry.sequence,
        username = entry.username,
        kind = entry.change.kind,
        recordedAt = entry.recordedAt.toString,
        itemId = entry.change.itemIdOpt,
        description = entry.change.descriptionOpt)
      entry.change match
        case TodoChange.Baseline(open, completed) => base.copy(open = Some(open), completed = Some(completed))
        case _                                    => base

  final case class FeedView(since: String, entries: List[EntryView])

  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class SetAsideEntryView(username: String, attempts: Int, reason: String, recordedAt: String)

  final case class SetAsideFeedView(since: String, setAsides: List[SetAsideEntryView])

  object SetAsideView:
    def of(record: SetAside): SetAsideEntryView =
      SetAsideEntryView(record.username, record.attempts, record.reason, record.recordedAt.toString)
    def wrap(since: String, records: List[SetAsideEntryView]): SetAsideFeedView = SetAsideFeedView(since, records)
