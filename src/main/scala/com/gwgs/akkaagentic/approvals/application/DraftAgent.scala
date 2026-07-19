package com.gwgs.akkaagentic.approvals.application

import akka.javasdk.agent.autonomous.{AgentDefinition, AutonomousAgent}
import akka.javasdk.agent.autonomous.capability.TaskAcceptance
import akka.javasdk.annotations.Component

/** Capability 5, upstream half: produces the candidate reply a human then reviews.
  *
  * Deliberately simple — no `@FunctionTool`, no knowledge base (that was cap-3). The point of this
  * capability is the **gate**, not the drafting (spec A-008): this agent exists so there is something
  * real for a person to approve or reject.
  *
  * It has no idea it is being gated. It completes its own task and stops; the runtime holds the
  * downstream publish task back because that task depends on the unassigned approval task
  * ([[ApprovalTasks.APPROVAL]]) — the gating lives in the dependency graph, not here (research R3).
  */
@Component(
  id = "draft-agent",
  description =
    "Drafts a candidate reply to a customer question and submits it for human review, without " +
      "sending anything to the customer."
)
class DraftAgent extends AutonomousAgent:

  override def definition(): AgentDefinition =
    define()
      .instructions(DraftAgent.Instructions)
      // Bound the loop so a task always terminates (spec edge case: the draft agent may abandon).
      .capability(TaskAcceptance.of(ApprovalTasks.DRAFT).maxIterationsPerTask(3))

object DraftAgent:

  /** Procedural guidance for the model (tone/rules — not orchestration; the loop is the runtime's). */
  private val Instructions: String =
    """You draft candidate replies to customer questions for a human reviewer to approve.
      |
      |Write a clear, polite, concise reply that directly answers the question. Assume a human will
      |read it before it ever reaches the customer, so do not address the reviewer or add commentary —
      |write the reply itself.
      |
      |When the draft is ready, complete the task with:
      |  - body: the drafted reply, ready for review.
      |
      |If you cannot draft a reply at all, fail the task with a brief reason rather than guessing.""".stripMargin
