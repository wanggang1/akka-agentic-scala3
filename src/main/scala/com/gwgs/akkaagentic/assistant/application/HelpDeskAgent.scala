package com.gwgs.akkaagentic.assistant.application

import akka.javasdk.agent.autonomous.{AgentDefinition, AutonomousAgent}
import akka.javasdk.agent.autonomous.capability.TaskAcceptance
import akka.javasdk.annotations.{Component, Description, FunctionTool}
import com.gwgs.akkaagentic.assistant.domain.KnowledgeBase

/** Capability 3: a single **Autonomous Agent** that answers a user's help question through a
  * model-driven iteration loop.
  *
  * Unlike a request-based `Agent` (cap-1) or a `Workflow` (cap-2), there is **no command handler and
  * no fixed step sequence**: the runtime drives the model until it completes the task. The model
  * decides for itself whether to consult the knowledge base (via [[lookupPolicy]]) and how often,
  * then finishes by calling the built-in `complete_task` tool with a typed [[HelpAnswer]].
  *
  * This is the first component on the roadmap authored in Scala for an orchestration-flavored
  * capability: the Autonomous Agent API is `Class`/`Task`/annotation-based with no method-reference
  * wall (specs/005 research R1), so — unlike the Workflow — it needs no Java.
  */
@Component(
  id = "help-desk-agent",
  description =
    "Answers a user's help question, consulting a knowledge base when useful, and returns a " +
      "structured answer with a category, the topics it cited, and a confidence score."
)
class HelpDeskAgent extends AutonomousAgent:

  override def definition(): AgentDefinition =
    define()
      .instructions(HelpDeskAgent.Instructions)
      // Accept the ANSWER task; bound the loop so a task always terminates (spec edge case).
      .capability(TaskAcceptance.of(HelpDeskTasks.ANSWER).maxIterationsPerTask(5))

  /** Look up a canned reference for a topic. The model calls this of its own accord when a question
    * maps to a known topic; a miss returns a clear "no entry" string (never an error), so the model
    * can still answer and simply omit the topic from `citedTopics`.
    *
    * Public on purpose: `@FunctionTool` methods are discovered by reflection, and a Scala `private`
    * def name-mangles in a way the scanner may miss (same reasoning as cap-1's tool).
    */
  @FunctionTool(
    name = "lookupPolicy", // explicit, stable name — used by the model and asserted in tests
    description =
      "Look up a canned knowledge-base entry for a topic (e.g. password-reset, billing, account, " +
        "shipping). Returns the reference text, or a 'no entry' message when the topic is unknown."
  )
  def lookupPolicy(
      @Description("The knowledge-base topic to look up, e.g. \"password-reset\".") topic: String
  ): String =
    KnowledgeBase
      .lookup(topic)
      .map(entry => s"[${entry.topic}] ${entry.summary}")
      .getOrElse(s"No knowledge-base entry for \"$topic\".")

object HelpDeskAgent:

  /** Procedural guidance for the model (tone/rules — not orchestration; the loop is the runtime's). */
  private val Instructions: String =
    """You are a help-desk assistant. Answer the user's question clearly and concisely.
      |
      |When the question relates to a known topic (such as password-reset, billing, account, or
      |shipping), call the lookupPolicy tool to consult the knowledge base before answering, and base
      |your answer on what it returns. If the question does not match a known topic, answer directly.
      |
      |When you have enough to answer, complete the task with:
      |  - answer: your response to the user.
      |  - category: a short classification of the question (e.g. account, billing, shipping, general).
      |  - citedTopics: the knowledge-base topics you actually consulted (empty if none).
      |  - confidence: your confidence in the answer, 0-100.
      |
      |If you cannot answer the question at all, fail the task with a brief reason rather than guessing.""".stripMargin
