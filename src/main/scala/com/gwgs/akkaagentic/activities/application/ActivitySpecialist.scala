package com.gwgs.akkaagentic.activities.application

import akka.javasdk.agent.Agent
import akka.javasdk.annotations.Component

/** A request-based specialist Agent that suggests activities suited to given conditions + preferences.
  *
  * The second **delegation worker**: the [[ActivityCoordinator]] delegates to it via `Delegation.to(...)`.
  * Its command handler receives the delegation brief (weather conditions + preferences the coordinator
  * passes along) and replies with activity ideas — pure reasoning, no tools. The `@Component`
  * `description` is the delegation-tool description the coordinator's model uses to pick it.
  */
@Component(
  id = "activity-specialist",
  description = "Suggests activities suited to given weather conditions and stated preferences."
)
class ActivitySpecialist extends Agent:

  def suggest(brief: String): Agent.Effect[String] =
    effects()
      .systemMessage(ActivitySpecialist.Instructions)
      .userMessage(brief)
      .onFailure((_: Throwable) => "No activity suggestions are available right now.")
      .thenReply()

object ActivitySpecialist:

  private val Instructions: String =
    """You are an activities expert. The request describes weather conditions and any preferences.
      |Suggest a few specific activities that fit those conditions and preferences, in one or two short
      |sentences. Assume the weather given is accurate — do not look it up.""".stripMargin
