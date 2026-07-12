package com.gwgs.akkaagentic.chat.application

import akka.javasdk.agent.{Agent, MemoryProvider}
import akka.javasdk.annotations.Component

object ChatAgent:

  private val SystemMessage: String =
    """You are a concise, friendly conversational assistant.
      |
      |You are in an ongoing conversation with one user. Earlier messages in this
      |conversation are provided to you as context — use them. In particular, if the
      |user has told you something earlier (their name, a preference, a fact) and later
      |asks about it, answer from that earlier context rather than claiming you don't
      |know. If nothing in the conversation so far answers the question, say so plainly
      |instead of inventing an answer.
      |
      |Keep replies short and natural — a sentence or two.""".stripMargin

/** Capability 4 — a single request-based conversational Agent.
  *
  * One model call per turn (`chat`), synchronous from the caller's view. Multi-turn context is not
  * managed here: the SDK's session memory — keyed by the session id the caller passes to
  * `.inSession(sessionId)` — automatically stores each user/AI message and replays prior turns as
  * context on the next call with the same id. This agent holds no state of its own.
  *
  * Payload is a bare `String` in / `String` out, so — unlike cap-1's `Result` and cap-3's
  * `HelpAnswer` — there is no Java-shaped wire type crossing the SDK's internal mapper (research R4).
  */
@Component(id = "chat-agent")
class ChatAgent extends Agent:
  import ChatAgent.*

  /** Reply to one message within the caller's session. The session id is supplied by the caller via
    * `.inSession(...)`; it never appears here — memory is entirely runtime-managed.
    */
  def chat(message: String): Agent.Effect[String] =
    effects()
      // Explicit for legibility — this equals the default (memory is enabled, limited-window is the
      // default policy). Named here because session memory is this capability's whole subject
      // (research R5). No `readLast(N)`: bounding is left to the default size window (FR-009).
      .memory(MemoryProvider.limitedWindow())
      .systemMessage(SystemMessage)
      .userMessage(message)
      .thenReply()
