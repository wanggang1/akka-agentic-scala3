package com.gwgs.akkaagentic.a2a.application

import akka.javasdk.annotations.{Description, FunctionTool}
import akka.javasdk.client.ComponentClient

/** The delegation tool a top-level personal assistant exposes to the model (capability 6).
  *
  * This is the positive interop result (research R1): one assistant invokes another **in idiomatic
  * Scala** through the agent `ComponentClient`'s `dynamicCall` — no method-ref wall (contrast the entity
  * client behind [[TodoTools]]). It is a plain tool object, **not** a component, and — crucially — not an
  * agent handed to `.tools()` (the SDK forbids passing an `Agent` class as a tool; we only ever call the
  * agent *through* the component client here). See research R7 for why we take this SDK-discouraged
  * "agent chaining" path deliberately.
  *
  * One-hop guard (research R4): every delegated call sets `delegated = true`, and the agent offers this
  * tool only when `!delegated`, so the target assistant cannot delegate onward. Constructed with the
  * caller's own `username` purely for a truthful attribution line.
  */
class ForwardTool(componentClient: ComponentClient, fromUsername: String):

  @FunctionTool(
    description =
      "Delegate a request to ANOTHER user's personal assistant, named by their username, and return " +
        "that assistant's reply. Use this when the user asks to ask, check with, or get something done " +
        "by another person's assistant. Do not use it for the current user's own to-dos."
  )
  def askAssistant(
      @Description("the target user's username (whose assistant should handle the request)")
      username: String,
      @Description("the request/question to send to that user's assistant, in plain language")
      question: String
  ): String =
    val reply = componentClient
      .forAgent()
      .inSession(username) // the delegate runs as the TARGET user (their history + their to-dos)
      .dynamicCall[PersonalAssistantAgent.Request, String]("personal-assistant-agent")
      .invoke(PersonalAssistantAgent.Request(username, question, delegated = true)) // one-hop guard
    s"$username's assistant: $reply"
