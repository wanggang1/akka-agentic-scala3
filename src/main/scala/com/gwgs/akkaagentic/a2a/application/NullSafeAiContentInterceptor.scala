package com.gwgs.akkaagentic.a2a.application

import akka.javasdk.agent.{SessionMemoryInterceptor, SessionMessage}

/** Normalizes a null-content AI message to empty text just before it is persisted to session memory.
  *
  * Guards against a small local model (e.g. qwen3:8b) emitting a tool-call turn with `content: null`
  * — see README cap-6 "Live caveat" and the [[qwen3-null-content-poisons-session]] finding. Verified
  * against SDK 3.6.0 sources: `SessionMessage.AiMessage.text` and the SPI DTOs (`ModelResponse.content`,
  * `ContextMessage.AiMessage.content`) all tolerate null, so such a turn is persisted **as-is** and only
  * blows up later when the live model provider replays it (mechanism A — the write precedes the throw).
  * Rewriting `text` null -> "" here — on the write path, before it lands in `SessionMemoryEntity` — keeps
  * the stored history convertible, so one bad turn can't durably poison the session. `toolCallRequests`
  * (and every other field) are preserved, so an in-flight tool call still runs.
  *
  * This is the root-cause complement to [[PersonalAssistantAgent]]'s `.onFailure` fallback: onFailure
  * degrades a failed turn to a clean reply; this stops the failure from recurring on every later turn.
  *
  * Stateless singleton: the SDK shares one interceptor instance across all sessions and concurrent
  * requests (see the interface's thread-safety note), so it must hold no mutable state.
  */
object NullSafeAiContentInterceptor extends SessionMemoryInterceptor:

  override def beforeWrite(sessionId: String, ai: SessionMessage.AiMessage): SessionMessage.AiMessage =
    if ai.text() != null then ai // common case: nothing to fix, avoid a needless copy
    else
      new SessionMessage.AiMessage(
        ai.timestamp(),
        "", // the fix: null content -> empty, keeping the stored history replay-safe
        ai.componentId(),
        ai.toolCallRequests(), // preserved so an in-flight tool call still executes
        ai.thinking(),
        ai.tokenUsage(),
        ai.attributes())
