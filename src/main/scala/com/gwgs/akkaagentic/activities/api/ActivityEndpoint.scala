package com.gwgs.akkaagentic.activities.api

import java.nio.charset.StandardCharsets
import java.util.UUID

import scala.jdk.CollectionConverters.*
import scala.util.Try

import akka.http.javadsl.model.{ContentTypes, HttpResponse, StatusCodes}
import akka.http.javadsl.model.headers.Location
import akka.javasdk.agent.task.TaskStatus
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{Get, HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.gwgs.akkaagentic.activities.application.{ActivityCoordinator, ActivitySuggestion, ActivityTasks}
import com.gwgs.akkaagentic.activities.domain.SuggestionQuestion

/** Async HTTP surface for capability 7 (the delegating activity coordinator). `POST /activities`
  * starts a single coordination task and returns a handle immediately; `GET /activities/{taskId}`
  * retrieves the typed suggestion once the task completes. Same start-then-poll contract as cap-3.
  */
object ActivityEndpoint:

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` fields. Absent/null
    * `location` → `None` (rejected by validation, not a 500). Unknown props tolerated. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class StartRequest(location: Option[String], preferences: Option[String])

  /** POST acknowledgement — the task id to poll. */
  final case class StartAccepted(taskId: String)

  /** Outbound suggestion — API-owned, mirrors [[ActivitySuggestion]] but keeps the wire contract
    * independent of the application layer (API isolation). Idiomatic Scala: `Option`/`List`, empty
    * `weatherConsidered` omitted. */
  final case class SuggestionReply(
      suggestion: String,
      weatherConsidered: Option[String],
      consultedSpecialists: List[String]
  )

  /** Map the Java-shaped task result to the idiomatic API wire type. */
  private def toApi(s: ActivitySuggestion): SuggestionReply =
    SuggestionReply(
      s.suggestion,
      Option(s.weatherConsidered).map(_.trim).filterNot(_.isBlank),
      s.consultedSpecialists.asScala.toList
    )

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class ActivityEndpoint(componentClient: ComponentClient):
  import ActivityEndpoint.*

  /** Start a coordination task. Validates the request first; invalid input returns `400` without
    * starting a task or invoking the model. On success, runs a single task on a fresh coordinator
    * instance and returns `202` + `Location` + the task id. */
  @Post("/activities")
  def start(request: StartRequest): HttpResponse =
    SuggestionQuestion.validate(request.location, request.preferences) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val taskId = componentClient
          .forAutonomousAgent(classOf[ActivityCoordinator], UUID.randomUUID().toString)
          .runSingleTask(ActivityTasks.SUGGEST.instructions(valid.instruction))
        HttpResponses.accepted(StartAccepted(taskId)).addHeader(Location.create("/activities/" + taskId))

  /** Retrieve the suggestion by task id. COMPLETED -> 200, FAILED -> 422, still-running or unknown
    * id -> 404. Never fabricates a suggestion. */
  @Get("/activities/{taskId}")
  def get(taskId: String): HttpResponse =
    Try(componentClient.forTask(taskId).get(ActivityTasks.SUGGEST)) match
      case scala.util.Success(snapshot) =>
        snapshot.status() match
          case TaskStatus.COMPLETED if snapshot.result().isPresent =>
            HttpResponses.ok(toApi(snapshot.result().get()))
          case TaskStatus.FAILED =>
            unprocessable(snapshot.failureReason().orElse("the coordinator could not produce a suggestion"))
          case _ =>
            HttpResponses.notFound("suggestion not ready")
      case scala.util.Failure(_) =>
        HttpResponses.notFound("suggestion not ready")

  /** 422 Unprocessable Content — a terminal task failure, distinct from 404 (not ready) and 200. */
  private def unprocessable(reason: String): HttpResponse =
    HttpResponses.of(
      StatusCodes.UNPROCESSABLE_ENTITY,
      ContentTypes.TEXT_PLAIN_UTF8,
      reason.getBytes(StandardCharsets.UTF_8)
    )
