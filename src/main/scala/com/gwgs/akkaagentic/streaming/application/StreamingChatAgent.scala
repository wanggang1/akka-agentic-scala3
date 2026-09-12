package com.gwgs.akkaagentic.streaming.application

import akka.javasdk.agent.{Agent, MemoryProvider}
import akka.javasdk.annotations.Component

/** Capability 14's agent: a conversation that answers by **streaming** its reply.
  *
  * The one structural difference from capability 4's `ChatAgent` is the return type — `StreamEffect`
  * rather than `Effect[String]` — built by `streamEffects()` instead of `effects()`. Everything the
  * builder takes is a value or a string (`systemMessage`, `userMessage`, `memory`), so **authoring a
  * streamed reply carries no method-reference wall** (research Q-A). Consuming one is the open
  * question, and it is the caller's problem, not the agent's.
  *
  * **There is no `onFailure` here, and that is not an omission.** `Agent.StreamEffect.Builder` offers
  * `error(...)` — a refusal decided *before* any token is emitted — and nothing at all for a failure
  * that lands after the first token has already reached the caller. Capability 8's
  * `.onFailure(_ => sentinel)` technique is therefore unavailable in principle, not merely unused:
  * a fallback value cannot replace text the caller has already read. What a caller actually observes
  * in both cases is measured in the probe rather than assumed.
  */
@Component(id = "streaming-chat-agent")
class StreamingChatAgent extends Agent:
  import StreamingChatAgent.*

  /** The single command handler. Returns `StreamEffect`, so the reply is delivered as tokens. */
  def stream(message: String): Agent.StreamEffect =
    streamEffects()
      .systemMessage(SystemMessage)
      // Same memory shape as capability 4: full history, no readLast(N) — that trim orphans
      // tool-call pairs (cap-6's live bug). Whether a STREAMED reply is written to session memory at
      // all is research Q-D, measured, not assumed.
      .memory(MemoryProvider.limitedWindow())
      .userMessage(message)
      .thenReply()

object StreamingChatAgent:

  /** Deliberately asks for a multi-sentence answer: a one-word reply would make "streamed" and
    * "not streamed" indistinguishable, and SC-001 needs an answer long enough to arrive in pieces. */
  private val SystemMessage: String =
    """You are a helpful assistant in an ongoing conversation. Answer in a few complete sentences, so
      |the reply is long enough to be read as it arrives. Use the conversation's earlier turns as
      |context when they are relevant.""".stripMargin
