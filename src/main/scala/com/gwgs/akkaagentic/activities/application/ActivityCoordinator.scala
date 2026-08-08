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
  * NOTE (T006 spike): wired to the [[WeatherSpecialist]] only, to verify the request-based-worker
  * delegation adaptation live before adding the `ActivitySpecialist` (T010/T011).
  */
@Component(
  id = "activity-coordinator",
  description =
    "Suggests activities for a location by consulting weather (and activity) specialists and " +
      "synthesizing a single recommendation."
)
class ActivityCoordinator extends AutonomousAgent:

  override def definition(): AgentDefinition =
    define()
      .instructions(ActivityCoordinator.Instructions)
      .capability(TaskAcceptance.of(ActivityTasks.SUGGEST).maxIterationsPerTask(5))
      .capability(Delegation.to(classOf[WeatherSpecialist]))

object ActivityCoordinator:

  private val Instructions: String =
    """You are an activity coordinator. The task names a location and optional preferences.
      |
      |Delegate to the weather specialist to get the current conditions for that location. Then, using
      |those conditions and the preferences, complete the task with:
      |  - suggestion: activities that fit the weather and the stated preferences.
      |  - weatherConsidered: the conditions you factored in.
      |  - consultedSpecialists: the specialists you actually consulted (e.g. "weather-specialist").
      |
      |If you cannot produce a suggestion, fail the task with a brief reason rather than guessing.""".stripMargin
