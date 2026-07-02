package com.gwgs.akkaagentic.application

import akka.javasdk.agent.Agent
import akka.javasdk.annotations.{Component, Description, FunctionTool}
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}
import com.gwgs.akkaagentic.domain.TimeOfDay

object GreetingAgent:

  /** The agent's single command parameter.
    *
    * Explicit Jackson annotations make round-tripping deterministic regardless of
    * which Jackson modules the SDK's `ObjectMapper` has registered (research R3).
    */
  final case class Request @JsonCreator() (
      @JsonProperty("user") user: String,
      @JsonProperty("text") text: String
  )

  /** The agent's structured reply.
    *
    * `@Description` on each field feeds the JSON schema that `responseConformsTo`
    * derives, so the model knows what to produce; `@JsonProperty` keeps
    * (de)serialization deterministic (research R1/R3).
    */
  final case class Result @JsonCreator() (
      @JsonProperty("greeting")
      @Description("The personalized greeting text addressed to the user, one or two sentences.")
      greeting: String,
      @JsonProperty("tone")
      @Description("A short label for the tone/intent detected in the user's message, e.g. casual, question, formal.")
      tone: String,
      @JsonProperty("timeOfDay")
      @Description("The current time of day: morning, afternoon, evening, or night.")
      timeOfDay: String
  )

  private val SystemMessage: String =
    """You are a friendly greeting assistant. Compose a single, warm, personalized
      |greeting that addresses the user by name and acknowledges their message.
      |
      |Detect the intent and tone of the user's message and match it in your greeting,
      |rather than using a fixed template:
      |  - If the message asks a question or requests help, acknowledge it warmly and
      |    signal that you're ready to help (without actually answering it here).
      |  - If the message is a casual hello or small talk, reply with an equally casual,
      |    upbeat greeting.
      |  - Mirror the user's formality and energy.
      |
      |Produce three fields:
      |  - greeting: the greeting text (one or two sentences),
      |  - tone: a short label for the tone/intent you detected,
      |  - timeOfDay: the current time of day (morning, afternoon, evening, or night).""".stripMargin

@Component(id = "greeting-agent")
class GreetingAgent extends Agent:
  import GreetingAgent.*

  /** Compose a personalized, structured greeting for an already-validated request. */
  def greet(request: Request): Agent.Effect[Result] =
    effects()
      .systemMessage(SystemMessage)
      .userMessage(
        s"""The user's name is "${request.user}".
           |They sent this message: "${request.text}".
           |Greet them.""".stripMargin
      )
      .responseConformsTo(classOf[Result])
      .thenReply()

  /** Reports the current time of day for an optional IANA timezone.
    *
    * Public (not private) on purpose: `@FunctionTool` methods are discovered by
    * reflection, and a Scala `private def` name-mangles in a way the scanner may not
    * find (research R3). Delegates to the pure [[TimeOfDay]] domain function; the
    * model-supplied timezone `String` is converted `null/"" -> None` at this boundary.
    */
  @FunctionTool(
    description =
      "Returns the current time of day (morning, afternoon, evening, or night) for an " +
        "optional IANA timezone id; falls back to UTC when the timezone is empty or unrecognized."
  )
  def currentTimeOfDay(
      @Description("IANA timezone id, e.g. \"America/New_York\". May be empty to use UTC.")
      timezone: String
  ): String =
    TimeOfDay.now(Option(timezone))
