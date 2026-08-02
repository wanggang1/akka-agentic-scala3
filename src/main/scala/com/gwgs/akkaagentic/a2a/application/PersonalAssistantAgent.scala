package com.gwgs.akkaagentic.a2a.application

import akka.javasdk.agent.{Agent, MemoryProvider}
import akka.javasdk.annotations.Component
import akka.javasdk.client.ComponentClient
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}
import org.slf4j.LoggerFactory

object PersonalAssistantAgent:

  private val logger = LoggerFactory.getLogger(classOf[PersonalAssistantAgent])

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

  /** Graceful-degradation reply (AGENTS.md agent checklist: tool/structured agents should have an
    * `onFailure`). A model or tool failure — notably a small local model emitting a **null-content
    * tool-call turn** that the runtime rejects (see README cap-6 "Live caveat" and
    * [[qwen3-null-content-poisons-session]]) — becomes this clean reply instead of a raw `500`. It does
    * NOT un-poison a session on its own (that's the memory interceptor's job); it bounds one bad turn to
    * a single friendly reply, and catches the *recurring* poisoned turns whose failure surfaces inside
    * this agent's own model call.
    */
  private val FailureReply =
    "Sorry, I couldn't complete that request just now. Please try again."

  /** System prompt. When `canDelegate`, the assistant is told it may forward to another user's assistant;
    * a delegated call omits both the forward tool and this instruction, so it cannot delegate onward.
    */
  private def systemMessage(username: String, canDelegate: Boolean): String =
    val delegation =
      if canDelegate then
        s"""
           |If the user asks you to ask, check with, or get something done by ANOTHER person's assistant,
           |use the delegation tool with that person's username, and relay their assistant's reply.""".stripMargin
      else ""
    s"""You are $username, a concise and helpful personal assistant.
       |You manage $username's personal to-do list. When the user asks, use the provided tools to
       |list, add, delete, or complete to-dos — never invent to-dos or ids, always use the tools.$delegation
       |After acting, reply in one or two short sentences confirming what you did, or answering the
       |question directly if no tool was needed.""".stripMargin

@Component(id = "personal-assistant-agent")
class PersonalAssistantAgent(componentClient: ComponentClient) extends Agent:
  import PersonalAssistantAgent.*

  /** Answer one message for `req.username`'s assistant. Chat history is replayed from session memory
    * keyed by the username (set by the caller via `.inSession(username)`); to-dos are managed through the
    * Java [[TodoTools]] seam; delegation goes through [[ForwardTool]].
    *
    * One-hop guard (research R4): a top-level request (`delegated = false`) is offered both tools; a
    * request that arrived *as a delegate* (`delegated = true`) is offered only the to-do tools, so it
    * structurally cannot delegate again — bounding any A→B→A chain to a single hop.
    */
  def request(req: Request): Agent.Effect[String] =
    val todoTools = new TodoTools(componentClient, req.username)
    val base = effects().memory(
      // Full session history (no readLast trim) + the null-content interceptor. We deliberately do NOT
      // use .readLast(N): the SDK's last-N trim (MemoryHistoryUtils.trimToLastN) is a naive subList that
      // ignores tool-call/response pairing, so once a tool-using session exceeds N messages it orphans a
      // ToolCallResponse at the window head — an invalid chat sequence the model provider rejects, surfaced
      // confusingly as "argument content is null". This was the actual cause of cap-6's live "poisoning"
      // (proven live: removing readLast fixes it). Tradeoff: unbounded token growth on long sessions;
      // compaction is the proper bound (future work). See README cap-6 "Live caveat".
      MemoryProvider.limitedWindow().withInterceptor(NullSafeAiContentInterceptor))
    // Offer the forward tool only to a top-level request — a delegate gets to-do tools alone (one hop).
    val withTools =
      if req.delegated then base.tools(todoTools)
      else base.tools(todoTools, new ForwardTool(componentClient, req.username))
    withTools
      .systemMessage(systemMessage(req.username, canDelegate = !req.delegated))
      .userMessage(req.message)
      .onFailure { error =>
        // Log the real cause — otherwise the fallback swallows it and failures are undiagnosable.
        logger.warn(s"Assistant request failed for [${req.username}]; returning fallback reply", error)
        FailureReply
      }
      .thenReply()
