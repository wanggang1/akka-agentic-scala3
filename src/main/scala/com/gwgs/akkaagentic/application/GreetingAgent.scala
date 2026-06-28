package com.gwgs.akkaagentic.application

import akka.javasdk.agent.Agent
import akka.javasdk.annotations.Component
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

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
      |Keep it to one or two sentences. Reply with the greeting text only.""".stripMargin

@Component(id = "greeting-agent")
class GreetingAgent extends Agent:
  import GreetingAgent.*

  /** Compose a personalized greeting for an already-validated request. */
  def greet(request: Request): Agent.Effect[String] =
    effects()
      .systemMessage(SystemMessage)
      .userMessage(
        s"""The user's name is "${request.user}".
           |They sent this message: "${request.text}".
           |Greet them.""".stripMargin
      )
      .thenReply()
