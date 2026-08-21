package com.gwgs.akkaagentic.docs.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Sanity checks guarding corpus integrity (pure domain): every passage has a non-blank body and a
  * unique source label, since retrieval cites by source and duplicate/blank labels would corrupt
  * citations. */
class KnowledgeCorpusTest:

  @Test
  def corpusIsNonEmpty(): Unit =
    assertThat(KnowledgeCorpus.passages.isEmpty).isFalse()

  @Test
  def everyPassageHasNonBlankSourceAndText(): Unit =
    KnowledgeCorpus.passages.foreach { p =>
      assertThat(p.source.isBlank).isFalse()
      assertThat(p.text.isBlank).isFalse()
    }

  @Test
  def sourceLabelsAreUnique(): Unit =
    val sources = KnowledgeCorpus.passages.map(_.source)
    assertThat(sources.distinct.size).isEqualTo(sources.size)
