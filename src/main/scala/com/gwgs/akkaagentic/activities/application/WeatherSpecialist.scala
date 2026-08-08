package com.gwgs.akkaagentic.activities.application

import akka.javasdk.agent.Agent
import akka.javasdk.annotations.{Component, Description, FunctionTool}
import com.gwgs.akkaagentic.activities.domain.WeatherData

/** A request-based specialist Agent that reports weather conditions for a location.
  *
  * It is a **delegation worker**: the [[ActivityCoordinator]] delegates to it via `Delegation.to(...)`.
  * Verified in specs/009 research D1 that a request-based `Agent` is a valid delegation target
  * (`Agent implements AgentDelegationWorker`), proven live in T006. The single command handler receives
  * the delegation brief (which names the location); the model calls [[currentConditions]] and replies
  * with a summary.
  *
  * The `@Component` `description` is what the coordinator's model sees as the delegation-tool
  * description, so it must clearly state this specialist's expertise (capabilities.html.md).
  */
@Component(
  id = "weather-specialist",
  description = "Reports current weather conditions for a given location."
)
class WeatherSpecialist extends Agent:

  def report(brief: String): Agent.Effect[String] =
    effects()
      .systemMessage(WeatherSpecialist.Instructions)
      .userMessage(brief)
      .onFailure((_: Throwable) => "Weather conditions are currently unavailable.")
      .thenReply()

  /** Canned current conditions for a location (offline, deterministic — spec FR-010). Public so the
    * `@FunctionTool` reflection scanner sees it (a Scala `private` def name-mangles). */
  @FunctionTool(
    name = "currentConditions",
    description = "Return the current (canned) weather conditions for a location."
  )
  def currentConditions(
      @Description("The location/city to report conditions for, e.g. \"Boston\".") location: String
  ): String =
    WeatherData.forLocation(location)

object WeatherSpecialist:

  private val Instructions: String =
    """You are a weather reporter. The request names a location.
      |Call the currentConditions tool with that location, then reply with a one-sentence summary of
      |the current conditions. Do not suggest activities.""".stripMargin
