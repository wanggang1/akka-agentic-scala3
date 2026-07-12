package com.gwgs.akkaagentic.assistant.api

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
import com.gwgs.akkaagentic.assistant.application.{HelpAnswer, HelpDeskAgent, HelpDeskTasks}
import com.gwgs.akkaagentic.assistant.domain.HelpQuestion

/** Async HTTP surface for capability 3 (the autonomous help-desk agent). {@code POST /help} starts a
  * single task and returns a handle immediately; {@code GET /help/&#123;taskId&#125;} retrieves the typed
  * answer once the task completes. Separate from — and independent of — capabilities 1 and 2.
  */
object HelpDeskEndpoint:

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` field. Absent/null
    * `question` deserializes to `None` (rejected by validation, not a 500). Unknown props tolerated.
    */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class AskRequest(question: Option[String])

  /** POST acknowledgement — the task id to poll. */
  final case class StartAccepted(taskId: String)

  /** Outbound answer — API-owned, mirrors [[HelpAnswer]] but keeps the wire contract independent of
    * the application layer (API isolation). Idiomatic Scala: `citedTopics` is a Scala `List`.
    */
  final case class HelpReply(answer: String, category: String, citedTopics: List[String], confidence: Int)

  /** Map the Java-shaped task result to the idiomatic API wire type. */
  private def toApi(a: HelpAnswer): HelpReply =
    HelpReply(a.answer, a.category, a.citedTopics.asScala.toList, a.confidence)

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class HelpDeskEndpoint(componentClient: ComponentClient):
  import HelpDeskEndpoint.*

  /** Start a help task. Validates the question first; invalid input returns `400` without starting a
    * task or invoking the model. On success, runs a single task on a fresh agent instance and returns
    * `202` + `Location` + the task id.
    */
  @Post("/help")
  def start(request: AskRequest): HttpResponse =
    HelpQuestion.validate(request.question) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val taskId = componentClient
          .forAutonomousAgent(classOf[HelpDeskAgent], UUID.randomUUID().toString)
          .runSingleTask(HelpDeskTasks.ANSWER.instructions(valid.text))
        // 202 Accepted: the answer is not ready yet; Location points at the eventual result.
        HttpResponses.accepted(StartAccepted(taskId)).addHeader(Location.create("/help/" + taskId))

  /** Retrieve the answer by its task id. Maps the task snapshot: COMPLETED -> 200, FAILED -> 422,
    * anything else (still running) or an unknown id -> 404. Never fabricates an answer.
    */
  @Get("/help/{taskId}")
  def get(taskId: String): HttpResponse =
    Try(componentClient.forTask(taskId).get(HelpDeskTasks.ANSWER)) match
      case scala.util.Success(snapshot) =>
        snapshot.status() match
          case TaskStatus.COMPLETED if snapshot.result().isPresent =>
            HttpResponses.ok(toApi(snapshot.result().get()))
          case TaskStatus.FAILED =>
            unprocessable(snapshot.failureReason().orElse("the agent could not answer the question"))
          case _ =>
            HttpResponses.notFound("help answer not ready")
      case scala.util.Failure(_) =>
        // Unknown/never-started id, or a transient read error: not ready, never a fabricated answer.
        HttpResponses.notFound("help answer not ready")

  /** 422 Unprocessable Content — a terminal task failure, distinct from 404 (not ready) and 200. */
  private def unprocessable(reason: String): HttpResponse =
    HttpResponses.of(
      StatusCodes.UNPROCESSABLE_ENTITY,
      ContentTypes.TEXT_PLAIN_UTF8,
      reason.getBytes(StandardCharsets.UTF_8)
    )
