package com.gwgs.akkaagentic.activities.application

import akka.javasdk.agent.autonomous.{AgentDefinition, AutonomousAgent}
import akka.javasdk.agent.autonomous.capability.{Delegation, TaskAcceptance}
import akka.javasdk.annotations.Component

/** Capability 7: an **Autonomous Agent** that suggests activities by **delegating** to specialist
  * agents the model chooses at runtime, then **synthesizing** their input into one typed result.
  *
  * This is the SDK-**recommended** dynamic-delegation primitive (`Delegation.to(...)`), the blessed
  * counterpart to cap-6's hand-rolled `ForwardTool` chaining. Scala-clean: the delegation surface is
  * keyed on `Class` refs and `Task` constants, no method-reference wall (specs/009 research D2).
  *
  * The model chooses which specialist(s) to consult per request (dynamic-by-class); it retains
  * ownership and synthesizes their replies into the typed [[ActivitySuggestion]].
  */
@Component(
  id = "activity-coordinator",
  description =
    "Suggests activities for a location by consulting weather and activity specialists and " +
      "synthesizing a single recommendation."
)
class ActivityCoordinator extends AutonomousAgent:

  override def definition(): AgentDefinition =
    define()
      .instructions(ActivityCoordinator.Instructions)
      .capability(TaskAcceptance.of(ActivityTasks.SUGGEST).maxIterationsPerTask(5))
      .capability(Delegation.to(classOf[WeatherSpecialist], classOf[ActivitySpecialist]))

object ActivityCoordinator:

  private val Instructions: String =
    """You are an activity coordinator. The task names a location and optional preferences.
      |
      |Consult your specialists as the request warrants:
      |  - the weather specialist for the current conditions at the location.
      |  - the activity specialist for ideas that fit those conditions and the preferences.
      |Do not answer weather or activity questions yourself — delegate to the specialist.
      |
      |Then complete the task with:
      |  - suggestion: the activities to recommend, reflecting the weather and the stated preferences.
      |  - weatherConsidered: the conditions you factored in (empty if you did not consult weather).
      |  - consultedSpecialists: exactly the specialists you consulted, by id
      |    (e.g. "weather-specialist", "activity-specialist").
      |
      |If you cannot produce a suggestion, fail the task with a brief reason rather than guessing.""".stripMargin
