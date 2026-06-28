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
