package com.gwgs.akkaagentic.eval.domain

/** The *reference text* a judge is given: the retrieved passages, rendered as one string.
  *
  * Pure by construction — **no Akka import** (Constitution II). It takes `(source, text)` pairs rather
  * than capability 8's `KnowledgeStore.Retrieved` so that `domain` depends on nothing in
  * `application`, which is the same reason capability 12's `AnswerRules` takes its markers as a
  * parameter.
  *
  * The rendering deliberately mirrors the shape `DocsAgent` builds into its own user message
  * (`[n] (source) text`, newline-separated, retrieval order preserved). That is not cosmetic: a
  * grounding verdict is only meaningful if the judge is shown **what the assistant was shown**
  * (FR-004), and matching the shape is the cheapest way to keep that true.
  */
object ReferenceText:

  /** Render passages in the order given. An empty list renders as the empty string. */
  def render(passages: List[(String, String)]): String =
    passages.zipWithIndex
      .map { case ((source, text), i) => s"[${i + 1}] ($source) $text" }
      .mkString("\n")

  /** True when there is no reference material to judge against. Whitespace counts as absent. */
  def isEmpty(text: String): Boolean = text == null || text.isBlank
