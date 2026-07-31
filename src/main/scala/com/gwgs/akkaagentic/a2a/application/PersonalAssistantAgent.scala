package com.gwgs.akkaagentic.a2a.application

import akka.javasdk.agent.{Agent, MemoryProvider}
import akka.javasdk.annotations.Component
import akka.javasdk.client.ComponentClient
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

object PersonalAssistantAgent:

  /** The agent's single command parameter — the internal, agent-to-agent wire type.
    *
    * Java-shaped (explicit Jackson annotations, like cap-1's `GreetingAgent.Request`) because it crosses
    * the SDK's *internal* mapper as a component payload (research R4 / feature-003 two-mapper). It is NOT
    * on the public API surface: the HTTP body is `{message}` and the endpoint fills in `username` (from the
    * path) and leaves `delegated` at its default.
    *
    * `delegated` is the one-hop loop guard (research R4): it defaults to `false` and is set `true` ONLY by
    * `ForwardTool` when one assistant calls another. The agent offers the forward tool only when
    * `!delegated`, so a delegated request structurally cannot delegate again. The API caller never sets it.
    */
  final case class Request @JsonCreator() (
      @JsonProperty("username") username: String,
      @JsonProperty("message") message: String,
      @JsonProperty("delegated") delegated: Boolean = false
  )

  /** Bounded chat-history window (research R5): replay only the last N messages to cap token growth.
    * Note a delegated reply and `listTodos` output both count against this window.
    */
  private val HistoryWindow = 10

  private def systemMessage(username: String): String =
    s"""You are $username, a concise and helpful personal assistant.
       |You manage $username's personal to-do list. When the user asks, use the provided tools to
       |list, add, delete, or complete to-dos — never invent to-dos or ids, always use the tools.
       |After acting, reply in one or two short sentences confirming what you did, or answering the
       |question directly if no tool was needed.""".stripMargin

@Component(id = "personal-assistant-agent")
class PersonalAssistantAgent(componentClient: ComponentClient) extends Agent:
  import PersonalAssistantAgent.*

  /** Answer one message for `req.username`'s assistant. Chat history is replayed from session memory
    * keyed by the username (set by the caller via `.inSession(username)`); to-dos are managed through
    * the Java [[TodoTools]] seam. (Delegation via `ForwardTool` is wired in a later step.)
    */
  def request(req: Request): Agent.Effect[String] =
    effects()
      .memory(MemoryProvider.limitedWindow().readLast(HistoryWindow))
      .tools(new TodoTools(componentClient, req.username))
      .systemMessage(systemMessage(req.username))
      .userMessage(req.message)
      .thenReply()
