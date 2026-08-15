package com.gwgs.akkaagentic.docs.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import com.gwgs.akkaagentic.docs.domain.KnowledgeCorpus

/** Offline, deterministic retrieval tests — the core RAG proof (SC-001, SC-002, SC-004).
  *
  * These use the REAL in-process ONNX embeddings over the demo corpus and NO model: they assert that
  * a paraphrased query (wording different from the source passage) retrieves the right passage by
  * *meaning*, and that two distinct questions retrieve *different* top passages. Because embeddings
  * are deterministic, "which passages ground an answer" is fully verifiable without a live model —
  * the clean counter-example to capability 7's un-mockable delegation.
  */
class KnowledgeStoreTest:

  // One store, seeded once from the full corpus (embedding-model load is the expensive step).
  private val store = new KnowledgeStore(KnowledgeCorpus.passages)

  private def topSource(question: String): String =
    store.retrieve(question, 3).head.source

  @Test
  def paraphrasedInCorpusQueryRetrievesTheRightPassageByMeaning(): Unit =
    // Wording deliberately unlike the passage ("survive a restart" / "without writing persistence").
    val top = topSource("what makes agent work survive a restart without me writing persistence code?")
    assertThat(top).isEqualTo("durability-tasks")

  @Test
  def sessionMemoryQuestionRetrievesTheSessionMemoryPassage(): Unit =
    // Note: a vaguer "how does the assistant remember a conversation?" ties with cap-6 (which also
    // "remembers the conversation") — a genuine semantic overlap. A query naming the session id
    // discriminates cleanly, which is the point: retrieval is by meaning, and close passages compete.
    val top = topSource("how is chat history replayed across requests with the same session id?")
    assertThat(top).isEqualTo("cap-4-session-memory")

  @Test
  def twoDistinctQuestionsRetrieveDifferentTopPassages(): Unit =
    val a = topSource("how does the coordinator pick which specialist to consult?")
    val b = topSource("why can some components only be written in Java, not Scala?")
    assertThat(a).isEqualTo("cap-7-activity-coordinator")
    assertThat(b).isEqualTo("interop-method-ref-wall")
    assertThat(a).isNotEqualTo(b) // retrieval discriminates by meaning, not a single fixed passage

  @Test
  def retrievalIsDeterministicAndBoundedByK(): Unit =
    val q = "how does the human approval gate hold back publishing?"
    val first = store.retrieve(q, 2)
    val second = store.retrieve(q, 2)
    assertThat(first.size).isEqualTo(2) // bounded by k
    assertThat(first.map(_.source)).isEqualTo(second.map(_.source)) // deterministic
    assertThat(first.head.source).isEqualTo("cap-5-approval-gate")
