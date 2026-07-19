package com.gwgs.akkaagentic.approvals.api

import java.nio.charset.StandardCharsets
import java.util.UUID

import scala.jdk.OptionConverters.*
import scala.util.Try

import akka.http.javadsl.model.{ContentTypes, HttpResponse, StatusCodes}
import akka.http.javadsl.model.headers.Location
import akka.javasdk.agent.task.{TaskDefinition, TaskStatus}
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{Get, HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonInclude}
import com.gwgs.akkaagentic.approvals.application.*
import com.gwgs.akkaagentic.approvals.domain.*

/** HTTP surface for capability 5 — the human-in-the-loop approval gate.
  *
  * Four routes, start → poll → decide → poll: `POST /approvals` starts a case, `GET /approvals/{caseId}`
  * reports its state, and `POST /approvals/{caseId}/approve|reject` is the human decision.
  *
  * **There is no Entity and no Workflow here.** A case is not stored anywhere by us: the three task ids
  * are *derived* from one `caseId`, so the whole chain is reconstructable from the URL path, and the
  * tasks themselves are the durable record. That is not merely tidy — an Entity would have reintroduced
  * the method-ref wall (an entity client is `.method(E::cmd)`-only, no `dynamicCall`) and forced this
  * capability into Java. Staying stateless is what keeps it Scala (specs/007 research R2).
  *
  * The gate itself is likewise not implemented here: `ApprovalTasks.PUBLISH` depends on
  * `ApprovalTasks.APPROVAL`, and the runtime will not start a task whose dependencies are unmet. This
  * endpoint only *opens* or *closes* the gate; it never orders the steps (FR-007, research R3).
  *
  * This class deliberately holds **no business logic**: it adapts SDK snapshots into domain
  * [[TaskOutcome]]s, asks [[ApprovalCase]] what that means, and renders the answer. The state machine
  * and the decision rule live in the domain, where they are unit-testable without a runtime.
  */
object ApprovalEndpoint:

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` field. Absent/null
    * `question` deserializes to `None` (rejected by validation, not a 500). Unknown props tolerated.
    */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class SubmitRequest(question: Option[String])

  /** POST acknowledgement — the handle to poll and to address decisions to. */
  final case class CaseAccepted(caseId: String)

  /** Inbound decision body. Every field optional: a bare `{}` is a valid approve/reject. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class DecisionRequest(note: Option[String])

  /** Outbound case state. `NON_ABSENT` omits empty `Option`s, so `awaiting-approval` carries only
    * `draft`, `published` only `reply`, and `rejected` only `note` — no `null`s on the wire, and the
    * absence of a `reply` before approval is observable rather than a null check.
    */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class CaseState(
      state: String,
      draft: Option[String] = None,
      reply: Option[String] = None,
      note: Option[String] = None
  )

  // The three task ids are derived, never stored (research R2).
  private def draftId(caseId: String): String = s"$caseId-draft"
  private def approvalId(caseId: String): String = s"$caseId-approval"
  private def publishId(caseId: String): String = s"$caseId-publish"

  /** The assignee label recorded on the gate. Per the SDK docs this identifies *who* decided; it is not
    * an agent reassignment. Reviewer identity is out of scope (spec A-007), so it is a constant.
    */
  private val Reviewer = "reviewer"

  /** Render a domain state as the wire payload. Total over [[CaseProgress]]. */
  private def toApi(progress: CaseProgress): CaseState =
    val state = progress.label
    progress match
      case CaseProgress.NotFound => CaseState(state)
      case CaseProgress.Drafting => CaseState(state)
      case CaseProgress.DraftFailed(reason) => CaseState(state, note = Some(reason))
      case CaseProgress.AwaitingApproval(draft) => CaseState(state, draft = Some(draft))
      case CaseProgress.Publishing(draft) => CaseState(state, draft = Some(draft))
      case CaseProgress.Published(reply) => CaseState(state, reply = Some(reply))
      case CaseProgress.Rejected(note) => CaseState(state, note = note)
      case CaseProgress.PublishFailed(draft, reason) =>
        CaseState(state, draft = Some(draft), note = Some(reason))

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class ApprovalEndpoint(componentClient: ComponentClient):
  import ApprovalEndpoint.*

  /** Start a gated case. Validates first: an invalid question returns `400` without creating any task or
    * invoking any model (FR-010). On success, creates all three tasks with their dependency wiring,
    * assigns the two agent tasks — and deliberately leaves the approval task **unassigned**, which is
    * what makes it a gate — then returns `202` + `Location` + the handle.
    */
  @Post("/approvals")
  def submit(request: SubmitRequest): HttpResponse =
    ApprovalQuestion.validate(request.question) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val caseId = UUID.randomUUID().toString
        val draft = draftId(caseId)
        val approval = approvalId(caseId)
        val publish = publishId(caseId)

        componentClient
          .forTask(draft)
          .create(ApprovalTasks.DRAFT.instructions(s"Draft a reply to this customer question: ${valid.text}"))
        componentClient
          .forTask(approval)
          .create(
            ApprovalTasks.APPROVAL
              .instructions("Review the drafted reply and approve or reject it.")
              .dependsOn(draft)
          )
        componentClient
          .forTask(publish)
          .create(ApprovalTasks.PUBLISH.instructions("Publish the approved reply.").dependsOn(approval))

        // Both agent tasks are assigned up front. The publish agent cannot run yet: its task depends on
        // the gate, and the runtime does not start a task with unmet dependencies.
        componentClient.forAutonomousAgent(classOf[DraftAgent], s"$caseId-draft-agent").assignTasks(draft)
        componentClient.forAutonomousAgent(classOf[PublishAgent], s"$caseId-publish-agent").assignTasks(publish)

        HttpResponses.accepted(CaseAccepted(caseId)).addHeader(Location.create("/approvals/" + caseId))

  /** Report the case state. Only an unknown handle is `404`; every lifecycle state is a distinct `200`
    * payload (FR-002, FR-003).
    */
  @Get("/approvals/{caseId}")
  def state(caseId: String): HttpResponse =
    ApprovalCase.progress(draftOutcome(caseId), gateOutcome(caseId), publishOutcome(caseId)) match
      case CaseProgress.NotFound => HttpResponses.notFound("approval case not found")
      case progress => HttpResponses.ok(toApi(progress))

  /** Human approves: assign the gate (recording who decided) and complete it with the decision, which
    * satisfies the publish task's dependency and lets it run (FR-005).
    */
  @Post("/approvals/{caseId}/approve")
  def approve(caseId: String, request: DecisionRequest): HttpResponse =
    onOpenGate(caseId) { gate =>
      componentClient.forTask(gate).assign(Reviewer)
      componentClient
        .forTask(gate)
        .complete(ApprovalTasks.APPROVAL, ApprovalDecision(true, request.note.getOrElse("")))
      HttpResponses.ok("approved")
    }

  /** Human rejects: assign the gate and **fail** it with the note as the reason. Failing a task with
    * dependents cancels them, so the publish task goes to `CANCELLED` and no reply is ever produced —
    * the runtime enforces this, not us (FR-006).
    */
  @Post("/approvals/{caseId}/reject")
  def reject(caseId: String, request: DecisionRequest): HttpResponse =
    onOpenGate(caseId) { gate =>
      componentClient.forTask(gate).assign(Reviewer)
      componentClient.forTask(gate).fail(request.note.getOrElse("rejected by reviewer"))
      HttpResponses.ok("rejected")
    }

  /** Apply a decision only when the domain says the gate is open; otherwise refuse distinctly. */
  private def onOpenGate(caseId: String)(action: String => HttpResponse): HttpResponse =
    ApprovalCase.decide(draftOutcome(caseId), gateOutcome(caseId)) match
      case Decision.Open => action(approvalId(caseId))
      case Decision.NotFound => HttpResponses.notFound("approval case not found")
      case Decision.NotAwaiting => conflict("case is not awaiting approval")
      case Decision.AlreadyDecided => conflict("case has already been decided")

  // ---------------------------------------------------------------------------------------------
  // SDK → domain adapters
  // ---------------------------------------------------------------------------------------------

  private def draftOutcome(caseId: String): TaskOutcome[String] =
    outcomeOf(draftId(caseId), ApprovalTasks.DRAFT)(_.body)

  private def gateOutcome(caseId: String): TaskOutcome[Unit] =
    outcomeOf(approvalId(caseId), ApprovalTasks.APPROVAL)(_ => ())

  private def publishOutcome(caseId: String): TaskOutcome[String] =
    outcomeOf(publishId(caseId), ApprovalTasks.PUBLISH)(_.reply)

  /** Collapse an SDK task snapshot into the domain's five-way outcome.
    *
    * A task that was never created surfaces as a thrown `CommandException("Task does not exist")`
    * (verified — specs/007 research R2), which is what distinguishes an unknown handle from a case
    * whose draft simply has not finished. `COMPLETED` without a result is not expected (a typed result
    * is always present once `resultConformsTo` is set) and is treated as still running rather than
    * fabricating a value.
    */
  private def outcomeOf[R, A](taskId: String, definition: TaskDefinition[R])(
      value: R => A
  ): TaskOutcome[A] =
    Try(componentClient.forTask(taskId).get(definition)).toOption match
      case None => TaskOutcome.Missing
      case Some(snapshot) =>
        snapshot.status() match
          case TaskStatus.PENDING => TaskOutcome.Pending
          case TaskStatus.ASSIGNED | TaskStatus.IN_PROGRESS => TaskOutcome.Running
          case TaskStatus.COMPLETED =>
            snapshot.result().toScala.map(r => TaskOutcome.Done(value(r))).getOrElse(TaskOutcome.Running)
          case TaskStatus.FAILED | TaskStatus.CANCELLED | TaskStatus.RESULT_REJECTED =>
            TaskOutcome.Failed(snapshot.failureReason().toScala.filterNot(_.isBlank))

  /** 409 Conflict — the decision was refused; distinct from 404 (unknown) and 200 (applied). */
  private def conflict(message: String): HttpResponse =
    HttpResponses.of(
      StatusCodes.CONFLICT,
      ContentTypes.TEXT_PLAIN_UTF8,
      message.getBytes(StandardCharsets.UTF_8)
    )
