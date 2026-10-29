package com.gwgs.akkaagentic.docs.application

import scala.jdk.CollectionConverters.*

import akka.javasdk.agent.{Agent, MemoryProvider}
import akka.javasdk.annotations.Component
import com.gwgs.akkaagentic.docs.application.DocsAgent.DontKnow

/** Capability 22 (fork B5): the **streaming** grounded-and-cited twin of cap-8's [[DocsAgent]] and
  * cap-21's [[CitingDocsAgent]].
  *
  * Same corpus, same retrieval, same grounding. The difference is the delivery: the answer streams to the
  * caller as tokens (`StreamEffect`, like cap-14's `StreamingChatAgent`) and the citation is honest (⊆
  * retrieved, like cap-21). The tension this agent resolves is that a streamed turn has **no
  * structured-output channel** (research R1): cap-21's `CitingDocsAgent` reports used sources in a
  * structured `usedSources` field, which does not exist here. So the only signal for which sources the
  * model used is an **inline `[n]` marker in the prose** — which this agent is therefore told to *emit*,
  * exactly reversing cap-21's follow-up (which suppressed markers in favour of the structured field,
  * research R6). The endpoint strips those markers before the caller reads the prose and resolves them
  * against the retrieved set, so the caller never sees a raw marker and a fabricated number cites nothing.
  *
  * **No `onFailure`, by the same principle as cap-14** (`StreamingChatAgent`): `StreamEffect.Builder` has
  * no fallback-value hook for a failure after the first token, and a sentinel cannot replace text already
  * read. The endpoint's `.recover` converts a stream failure into a terminal `event: error` frame instead
  * (research R5).
  *
  * **`MemoryProvider.none()`**: each question is independent; this surface carries no conversation, so
  * there is no session history to read or write (it would only add non-determinism to the measurement).
  * A separate component from cap-8/21 on purpose — the one-handler rule and an untouched cap-8/21 (FR-001).
  */
object StreamingCitedDocsAgent:

  private val SystemMessage: String =
    s"""You are a documentation assistant. Answer the user's question using ONLY the numbered sources
       |provided in the user message. Each source is shown as: [n] (label) text. Do not use any outside
       |knowledge, and do not guess.
       |
       |Answer concisely in one or two sentences, grounded strictly in the sources. Immediately after each
       |piece of information, cite the source you used by writing its number in square brackets, e.g. [1]
       |or [2]. Write the bracketed number exactly as shown — just the number, like [1]. Cite a source
       |only where your answer genuinely relied on it, and cite every source you did use.
       |
       |If the sources do not contain enough information to answer the question, reply with EXACTLY the
       |following text and nothing else (no brackets, no citations):
       |$DontKnow""".stripMargin

  /** Render the question plus a numbered, source-labeled block of the retrieved passages — the same shape
    * cap-8/21 use, so the `[n]` the model is asked to cite lines up with the presented set. */
  private def userMessage(request: DocsAgent.Request): String =
    val sources = request.passages.asScala.toList.zipWithIndex
      .map { case (p, i) => s"[${i + 1}] (${p.source}) ${p.text}" }
      .mkString("\n")
    s"""Question: ${request.question}
       |
       |Sources:
       |$sources""".stripMargin

@Component(id = "streaming-cited-docs-agent")
class StreamingCitedDocsAgent extends Agent:
  import StreamingCitedDocsAgent.*

  /** The single command handler. Returns `StreamEffect`, so the grounded answer is delivered as tokens;
    * the endpoint strips the inline `[n]` markers and emits the terminal citations frame. */
  def stream(request: DocsAgent.Request): Agent.StreamEffect =
    streamEffects()
      .systemMessage(SystemMessage)
      .memory(MemoryProvider.none())
      .userMessage(userMessage(request))
      .thenReply()
