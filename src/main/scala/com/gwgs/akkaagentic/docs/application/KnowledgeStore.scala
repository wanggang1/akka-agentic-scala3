package com.gwgs.akkaagentic.docs.application

import scala.jdk.CollectionConverters.*

import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore

import com.gwgs.akkaagentic.docs.domain.{KnowledgeCorpus, Passage}

object KnowledgeStore:

  /** A retrieved passage plus its similarity [[score]] (higher = closer). */
  final case class Retrieved(source: String, text: String, score: Double)

  /** Metadata key under which a passage's source label is stored on its text segment. */
  private val SourceKey = "source"

  /** Build a store seeded from the fixed demo corpus (the normal construction). */
  def fromCorpus(): KnowledgeStore = new KnowledgeStore(KnowledgeCorpus.passages)

/** In-process semantic retrieval over a fixed corpus. Holds a quantized all-MiniLM-L6-v2 embedding
  * model (an ONNX model packaged in-jar — no network, no API key) and an in-memory vector store,
  * both seeded at construction. This is a plain utility, NOT an Akka component: it is provided as a
  * dependency via `Bootstrap`'s `DependencyProvider` and constructor-injected where needed.
  *
  * Retrieval is deterministic for a given corpus and question, so *which* passages ground an answer
  * is verifiable offline without invoking a model (FR-009) — the heart of the RAG proof.
  */
final class KnowledgeStore(corpus: List[Passage]):
  import KnowledgeStore.*

  private val embeddingModel: EmbeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel()
  private val store = new InMemoryEmbeddingStore[TextSegment]()

  // Seed the vector store once, at construction: embed each passage's text and index it with its
  // source label carried in segment metadata (so a match can be cited back to its passage).
  corpus.foreach { passage =>
    val segment = TextSegment.from(passage.text, new Metadata().put(SourceKey, passage.source))
    store.add(embeddingModel.embed(segment).content(), segment)
  }

  /** Retrieve up to `k` passages most semantically similar to `question`, most similar first. */
  def retrieve(question: String, k: Int): List[Retrieved] =
    val queryEmbedding = embeddingModel.embed(question).content()
    val request = EmbeddingSearchRequest
      .builder()
      .queryEmbedding(queryEmbedding)
      .maxResults(Integer.valueOf(k))
      .build()
    store
      .search(request)
      .matches()
      .asScala
      .toList
      .map { m =>
        val segment = m.embedded()
        Retrieved(segment.metadata().getString(SourceKey), segment.text(), m.score().doubleValue())
      }
