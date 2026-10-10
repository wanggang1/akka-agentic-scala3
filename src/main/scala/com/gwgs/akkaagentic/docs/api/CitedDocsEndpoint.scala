package com.gwgs.akkaagentic.docs.api

import java.util.UUID

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.gwgs.akkaagentic.docs.application.{CitingDocsAgent, DocsAgent, KnowledgeStore}
import com.gwgs.akkaagentic.docs.domain.{AskQuestion, UsageCitations}

/** Capability 21 (fork B4, feature 023). `POST /cited-ask` — the **usage-accurate** twin of cap-8's `/ask`.
  *
  * Same corpus, same retrieval, same grounding. The difference is the citation: cap-8 cites every
  * retrieved passage (ground truth, over-inclusive); this surface asks [[CitingDocsAgent]] which sources
  * it actually used and cites only those, intersected with what was retrieved
  * ([[UsageCitations]] — the honesty floor). The reply carries **both** lists (`citedSources` and
  * `retrievedSources`) so the divergence between used and retrieved is visible from one response, which
  * is what makes the self-report faithfulness measurable (spec SC-005/FR-006).
  *
  * A separate endpoint from [[DocsEndpoint]] on purpose (research R7): cap-8's `/ask` is left byte-for-byte
  * untouched (FR-001). Retrieval and validation are reused.
  */
object CitedDocsEndpoint:

  /** How many passages to retrieve and offer as grounding context — same K as cap-8. */
  private val TopK = 3

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` field. Absent/null
    * `question` deserializes to `None` (rejected by validation, not a 500). Unknown props tolerated. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class CitedAskRequest(question: Option[String])

  /** Outbound reply — API-owned. `citedSources` is usage-accurate (reported-used ∩ retrieved, empty on a
    * decline or when nothing verifiable was used); `retrievedSources` is the ground-truth set cap-8's
    * `/ask` would cite, kept alongside for comparison (FR-006). */
  final case class CitedAskReply(
      answer: String,
      citedSources: List[String],
      retrievedSources: List[String]
  )

  /** True when the agent's answer is the decline sentinel (normalized: trimmed, case-insensitive). A
    * decline must cite nothing — mirrors cap-8's `DocsEndpoint.isDecline`. */
  private def isDecline(answer: String): Boolean =
    answer.trim.toLowerCase.startsWith(DocsAgent.DontKnow.toLowerCase)

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class CitedDocsEndpoint(componentClient: ComponentClient, knowledgeStore: KnowledgeStore):
  import CitedDocsEndpoint.*

  /** Answer a question grounded in retrieved passages and cite only the sources the model reports using.
    * Validates first — a blank/absent question returns `400` with no retrieval and no model call
    * (FR-008). A decline, or a failed/malformed model turn (absorbed by the agent into a decline shape),
    * cites nothing.
    */
  @Post("/cited-ask")
  def ask(request: CitedAskRequest): HttpResponse =
    AskQuestion.validate(request.question) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val retrieved = knowledgeStore.retrieve(valid.question, TopK)
        val retrievedLabels = retrieved.map(_.source).distinct
        val agentRequest = DocsAgent.Request(
          valid.question,
          retrieved.map(r => DocsAgent.Passage(r.source, r.text)).asJava
        )
        val reply = componentClient
          .forAgent()
          .inSession(UUID.randomUUID().toString) // each question independent (FR-009); fresh session
          .dynamicCall[DocsAgent.Request, CitingDocsAgent.CitedAnswer]("citing-docs-agent")
          .invoke(agentRequest)

        val cited = UsageCitations.select(
          reportedUsed = Option(reply.usedSources).map(_.asScala.toList).getOrElse(Nil),
          retrievedLabels = retrievedLabels,
          declined = isDecline(reply.answer)
        )
        HttpResponses.ok(CitedAskReply(reply.answer, cited, retrievedLabels))
