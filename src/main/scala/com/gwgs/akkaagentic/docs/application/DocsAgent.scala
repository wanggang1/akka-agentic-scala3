package com.gwgs.akkaagentic.docs.application

import scala.jdk.CollectionConverters.*

import akka.javasdk.agent.{Agent, Guardrail}
import akka.javasdk.annotations.Component
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}
import org.slf4j.LoggerFactory

object DocsAgent:

  /** The exact text the model must reply when the supplied sources do not answer the question. The
    * endpoint keys its citation decision on this sentinel: a decline cites nothing (FR-005). Kept as a
    * shared constant so the agent's instruction and the endpoint's check can never drift apart.
    */
  val DontKnow: String = "I don't know"

  /** Marks a reply that is **not** an answer but a guardrail refusal, followed by the rule's own
    * explanation.
    *
    * Capability 12 needs a block to reach [[com.gwgs.akkaagentic.docs.api.DocsEndpoint]] intact, and
    * measurement showed the obvious channel does not work: rethrowing from `onFailure` is caught by
    * the SDK as a *"Failure mapping error"* (`AK-01203`) and reaches the caller as an opaque
    * `kalix.runtime.CorrelatedRuntimeException` — the `Guardrail.GuardrailException` type is gone by
    * then, so the endpoint cannot tell governance from any other failure (research R3-RESOLVED).
    *
    * The reply channel survives intact, so the block travels there instead, behind a prefix no model
    * output can plausibly produce. This is the same sentinel technique as [[DontKnow]] — shared as a
    * constant so the agent and the endpoint cannot drift apart.
    */
  val BlockedPrefix: String = "__guardrail-blocked__:"

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

  private val logger = LoggerFactory.getLogger(classOf[DocsAgent])

  /** Answer the question grounded only in the supplied passages, or reply with the [[DontKnow]]
    * sentinel. Retrieval and citation happen in the endpoint (research R4); this agent just grounds.
    * An ordinary failed turn degrades to the sentinel (a clean decline) rather than a 500 — AGENTS.md
    * checklist. A **guardrail block does not**: see [[onFailure]].
    */
  def ask(request: Request): Agent.Effect[String] =
    effects()
      .systemMessage(SystemMessage)
      .userMessage(userMessage(request))
      .onFailure(onFailure)
      .thenReply()

  /** Capability 12's one edit to capability 8, and the narrowest one that works.
    *
    * `onFailure` used to absorb *every* throwable into [[DontKnow]], which was correct while the only
    * throwables were model failures. Guardrails changed that: the runtime aborts a blocked interaction
    * by failing the effect, and `AgentImpl` converts the SPI `GuardrailFailure` into a
    * `Guardrail.GuardrailException` **before** this handler runs (research R3-RESOLVED). Absorbing it
    * would report a governance event to the caller as *"the corpus doesn't cover this"* — unauditable
    * and actively misleading, which is what FR-005 forbids. Measured, not assumed: the T003 probe
    * observed exactly that swallowing before this narrowing existed.
    *
    * A block is therefore re-emitted behind [[BlockedPrefix]] rather than rethrown — see that
    * constant for why the exception itself cannot cross the component-client boundary. Every other
    * failure keeps cap-8's honest-decline behaviour, now with a log line, since a silently swallowed
    * exception is how capability 6's real bug stayed invisible.
    */
  private def onFailure(failure: Throwable): String = failure match
    case block: Guardrail.GuardrailException =>
      // The runtime has already logged the full audit line (name, category, use-for); this records
      // what the *agent* did about it.
      logger.warn("docs-agent interaction blocked by a guardrail: {}", block.getMessage)
      BlockedPrefix + Option(block.getMessage).getOrElse("")
    case other =>
      logger.warn("docs-agent turn failed; degrading to the decline sentinel", other)
      DontKnow

  /** Render the question plus a numbered, source-labeled block of the retrieved passages. */
  private def userMessage(request: Request): String =
    val sources = request.passages.asScala.toList.zipWithIndex
      .map { case (p, i) => s"[${i + 1}] (${p.source}) ${p.text}" }
      .mkString("\n")
    s"""Question: ${request.question}
       |
       |Sources:
       |$sources""".stripMargin
