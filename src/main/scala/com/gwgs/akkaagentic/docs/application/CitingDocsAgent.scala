package com.gwgs.akkaagentic.docs.application

import scala.jdk.CollectionConverters.*

import akka.javasdk.agent.Agent
import akka.javasdk.annotations.Component
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}
import com.gwgs.akkaagentic.docs.application.DocsAgent.DontKnow
import org.slf4j.LoggerFactory

/** Capability 21 (fork B4, feature 023): the usage-reporting twin of [[DocsAgent]].
  *
  * [[DocsAgent]] answers grounded in the retrieved passages and the endpoint cites **everything it
  * retrieved** — ground truth, but over-inclusive. This agent instead asks the model which sources it
  * actually **used**, as structured output ([[CitedAnswer]]), so the endpoint can cite only those.
  *
  * This deliberately reopens capability 7's D6 self-report tension (a model naming its own sources is
  * unreliable, especially small ones). The defence is not to trust the model: the endpoint resolves each
  * reported source number against what was actually retrieved
  * ([[com.gwgs.akkaagentic.docs.domain.RetrievedSources]]), so self-report can only narrow the
  * ground-truth set, never invent a citation. Measuring how faithful that self-report is on `qwen3:8b` is
  * the capability's real deliverable (spec SC-005).
  *
  * A separate component from `docs-agent` on purpose (research R2): an `Agent` allows one command handler
  * and [[DocsAgent.ask]] returns a bare `String`, so a structured reply needs its own agent — which also
  * leaves cap-8 literally untouched (FR-001). The input [[DocsAgent.Request]]/[[DocsAgent.Passage]] and
  * the [[DocsAgent.DontKnow]] decline sentinel are reused read-only.
  */
object CitingDocsAgent:

  /** The structured object the model must emit. Java-shaped: an agent reply crosses the SDK's *internal*
    * mapper, which is not Scala-aware (README §3). `usedSources` is the list of **source numbers** `[n]`
    * the model reports using — **self-reported and untrusted**: the endpoint resolves each number against
    * the presented set ([[com.gwgs.akkaagentic.docs.domain.RetrievedSources]]) before anything is cited,
    * so an out-of-range or fabricated number cites nothing.
    *
    * Numbers rather than label strings on purpose (cap-21 live finding R6): on `qwen3:8b` the model
    * reliably knows which source it used (it emits an inline `[n]`) but inconsistently mirrors the
    * *label* into the structured field. Reporting the number it already tracks is the thing this contract
    * change tests.
    */
  final case class CitedAnswer @JsonCreator() (
      @JsonProperty("answer") answer: String,
      @JsonProperty("usedSources") usedSources: java.util.List[Integer]
  )

  private val SystemMessage: String =
    s"""You are a documentation assistant. Answer the user's question using ONLY the numbered sources
       |provided in the user message. Each source is shown as: [n] (label) text. Do not use any outside
       |knowledge, and do not guess.
       |
       |Your response must be a single JSON object with exactly these fields:
       |- "answer": your concise answer in one or two sentences, grounded strictly in the sources. Write
       |  the answer as plain prose only. Do NOT put source markers like [1] or (label) in the answer
       |  text; report which sources you used in the "usedSources" field instead.
       |- "usedSources": a JSON array of the source NUMBERS (the n in each [n]) of ONLY the sources you
       |  actually used to compose the answer, e.g. [1, 3]. Use the integers, not the labels. Include a
       |  number only if your answer genuinely relied on that source; omit the ones you did not use.
       |
       |If the sources do not contain enough information to answer the question, set "answer" to EXACTLY
       |"$DontKnow" and "usedSources" to an empty array [].""".stripMargin

@Component(id = "citing-docs-agent")
class CitingDocsAgent extends Agent:
  import CitingDocsAgent.*

  private val logger = LoggerFactory.getLogger(classOf[CitingDocsAgent])

  /** Answer grounded only in the supplied passages and report which ones were used, or decline with the
    * [[DocsAgent.DontKnow]] sentinel and no used sources. A failed or malformed turn is absorbed into the
    * same decline shape rather than becoming a 500 (research R3): this surface has no evaluator consumer,
    * so — unlike cap-8 — a failure and a decline are indistinguishable here, which is the honest contract
    * for `/cited-ask`. The log line keeps a swallowed exception from going invisible (cap-6's bug).
    */
  def ask(request: DocsAgent.Request): Agent.Effect[CitedAnswer] =
    effects()
      .systemMessage(SystemMessage)
      .userMessage(userMessage(request))
      .responseConformsTo(classOf[CitedAnswer])
      .onFailure(onFailure)
      .thenReply()

  private def onFailure(failure: Throwable): CitedAnswer =
    logger.warn("citing-docs-agent turn failed; replying as a decline that cites nothing", failure)
    CitedAnswer(DontKnow, java.util.List.of())

  /** Render the question plus a numbered, source-labeled block of the retrieved passages — the same
    * shape cap-8 uses, so the `(label)` tokens the model is asked to echo are unambiguous. */
  private def userMessage(request: DocsAgent.Request): String =
    val sources = request.passages.asScala.toList.zipWithIndex
      .map { case (p, i) => s"[${i + 1}] (${p.source}) ${p.text}" }
      .mkString("\n")
    s"""Question: ${request.question}
       |
       |Sources:
       |$sources""".stripMargin
