package com.gwgs.akkaagentic.approvals.application

import akka.javasdk.agent.autonomous.{AgentDefinition, AutonomousAgent}
import akka.javasdk.agent.autonomous.capability.TaskAcceptance
import akka.javasdk.annotations.Component

/** Capability 5, downstream half: produces the final reply — but only for an approved case.
  *
  * This agent is assigned its task at submit time, alongside [[DraftAgent]], yet it cannot run until a
  * human approves: its task depends on [[ApprovalTasks.APPROVAL]], and the runtime does not start a task
  * whose dependencies are unmet. If the reviewer *rejects*, the approval task fails and this agent's task
  * is auto-cancelled — so a [[PublishedReply]] can never exist for a rejected case (FR-006, FR-007).
  *
  * That guarantee is enforced by the runtime, not by this class or by any ordering in our endpoint code.
  *
  * Kept intentionally light (spec A-008): finalizing the approved draft, not re-generating it. Note that
  * task dependencies gate *ordering*, not context — the draft text is not automatically handed to this
  * agent, and wiring it through is a deliberate non-goal (research R5).
  */
@Component(
  id = "publish-agent",
  description =
    "Publishes a customer reply that a human reviewer has already approved, performing only light " +
      "finalization of the approved text."
)
class PublishAgent extends AutonomousAgent:

  override def definition(): AgentDefinition =
    define()
      .instructions(PublishAgent.Instructions)
      .capability(TaskAcceptance.of(ApprovalTasks.PUBLISH).maxIterationsPerTask(3))

object PublishAgent:

  private val Instructions: String =
    """You publish customer replies that a human reviewer has already approved.
      |
      |The decision to send has already been made by a person — your job is only to produce the final
      |text. Do not second-guess the approval, and do not add commentary or a preamble.
      |
      |Complete the task with:
      |  - reply: the final reply to the customer.""".stripMargin
