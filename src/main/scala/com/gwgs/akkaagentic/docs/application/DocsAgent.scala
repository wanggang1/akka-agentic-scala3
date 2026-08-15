package com.gwgs.akkaagentic.docs.application

import scala.jdk.CollectionConverters.*

import akka.javasdk.agent.Agent
import akka.javasdk.annotations.Component
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

object DocsAgent:

  /** The exact text the model must reply when the supplied sources do not answer the question. The
    * endpoint keys its citation decision on this sentinel: a decline cites nothing (FR-005). Kept as a
    * shared constant so the agent's instruction and the endpoint's check can never drift apart.
    */
  val DontKnow: String = "I don't know"

  /** A wire copy of a retrieved passage handed to the agent as grounding context.
    *
    * Java-shaped on purpose: an agent `Request` (and its nested types) crosses the SDK's *internal*
    * mapper, which is not Scala-aware (README §3), so it keeps explicit Jackson annotations. The
    * domain `Passage` stays idiomatic; this is its wire twin.
    */
  final case class Passage @JsonCreator() (
      @JsonProperty("source") source: String,
      @JsonProperty("text") text: String
  )

  /** The agent's single command parameter (Java-shaped, per above). */
  final case class Request @JsonCreator() (
      @JsonProperty("question") question: String,
      @JsonProperty("passages") passages: java.util.List[Passage]
  )

  private val SystemMessage: String =
    s"""You are a documentation assistant. Answer the user's question using ONLY the numbered sources
       |provided in the user message. Do not use any outside knowledge, and do not guess.
       |
       |If the sources do not contain enough information to answer the question, reply with EXACTLY the
       |following text and nothing else:
       |$DontKnow
       |
       |Otherwise, answer concisely in one or two sentences, grounded strictly in the sources.""".stripMargin

@Component(id = "docs-agent")
class DocsAgent extends Agent:
  import DocsAgent.*

  /** Answer the question grounded only in the supplied passages, or reply with the [[DontKnow]]
    * sentinel. Retrieval and citation happen in the endpoint (research R4); this agent just grounds.
    * A failed turn degrades to the sentinel (a clean decline) rather than a 500 — AGENTS.md checklist.
    */
  def ask(request: Request): Agent.Effect[String] =
    effects()
      .systemMessage(SystemMessage)
      .userMessage(userMessage(request))
      .onFailure(_ => DontKnow)
      .thenReply()

  /** Render the question plus a numbered, source-labeled block of the retrieved passages. */
  private def userMessage(request: Request): String =
    val sources = request.passages.asScala.toList.zipWithIndex
      .map { case (p, i) => s"[${i + 1}] (${p.source}) ${p.text}" }
      .mkString("\n")
    s"""Question: ${request.question}
       |
       |Sources:
       |$sources""".stripMargin
