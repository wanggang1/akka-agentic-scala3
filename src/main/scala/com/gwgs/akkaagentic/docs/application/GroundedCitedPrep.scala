package com.gwgs.akkaagentic.docs.application

import scala.jdk.CollectionConverters.*

import com.gwgs.akkaagentic.docs.api.GroundedSseEvent
import com.gwgs.akkaagentic.docs.domain.RetrievedSources
import com.gwgs.akkaagentic.docs.domain.RetrievedSources.SourceIndex

/** Capability 22 (fork B5): the Java-callable bridge between the forced-Java SSE endpoint and the Scala
  * retrieval + honesty-floor machinery (research R7).
  *
  * The endpoint ([[com.gwgs.akkaagentic.docs.api.StreamingCitedDocsEndpoint]]) is Java only because
  * consuming an agent's `tokenStream` needs a Java method reference (README §16). Everything it would
  * otherwise have to touch here — `KnowledgeStore.retrieve`'s Scala `List`, the `RetrievedSources` opaque
  * newtypes, the `DocsAgent.Request` construction — is reached through these two Java-shaped helpers, so
  * no Scala `List` or opaque type crosses into the Java class. This is cap-20's shape: the wall claims the
  * endpoint and the wire type and travels no further.
  *
  * The retrieval and the floor are identical to cap-21's non-streamed `/cited-ask`; only the delivery
  * differs. Keeping them here (not in the endpoint) keeps them provable without a runtime (constitution
  * §II/§III) and keeps `RetrievedSources`' opaque-newtype guarantee intact.
  */
object GroundedCitedPrep:

  /** Everything the endpoint needs to start the stream, in Java-shaped form: the agent request to send,
    * the [[RetrievedSources]] to apply the floor against at the end, and the distinct retrieved labels for
    * the terminal frame. `sources` stays a Scala value (opaque newtypes inside) but the endpoint only ever
    * passes it back to [[citationsEvent]], never inspecting it. */
  final case class Prepared(
      request: DocsAgent.Request,
      sources: RetrievedSources,
      retrievedLabels: java.util.List[String]
  )

  /** Retrieve `topK` passages for the question and build the agent request, the presented-source set the
    * model numbers `[n]` against (same order and multiplicity as the agent is shown — no dedup), and the
    * distinct retrieved labels shown in the citations frame. Mirrors cap-21's prep exactly. */
  def prepare(store: KnowledgeStore, question: String, topK: Int): Prepared =
    val retrieved = store.retrieve(question, topK)
    val request = DocsAgent.Request(
      question,
      retrieved.map(r => DocsAgent.Passage(r.source, r.text)).asJava
    )
    val sources = RetrievedSources.of(retrieved.map(_.source))
    Prepared(request, sources, retrieved.map(_.source).distinct.asJava)

  /** Build the terminal citations frame, applying the honesty floor: `usedSources` are the labels for the
    * numbers the model actually reported, resolved against what was presented (an out-of-range or
    * fabricated number resolves to nothing), de-duplicated, in retrieval order — empty on a decline or
    * when nothing verifiable was reported (FR-007/FR-008). `retrievedSources` is the full ground-truth
    * set, carried alongside for comparison. All opaque-newtype conversion stays on this Scala side. */
  def citationsEvent(
      sources: RetrievedSources,
      reportedNumbers: java.util.List[Integer],
      declined: Boolean,
      retrievedLabels: java.util.List[String]
  ): GroundedSseEvent.Citations =
    val reportedIndexes =
      Option(reportedNumbers).map(_.asScala.toList.map(n => SourceIndex(n.intValue))).getOrElse(Nil)
    val used = sources.cite(reportedIndexes, declined).map(_.value)
    new GroundedSseEvent.Citations(used.asJava, retrievedLabels)
