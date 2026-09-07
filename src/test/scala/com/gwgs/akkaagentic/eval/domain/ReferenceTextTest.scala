package com.gwgs.akkaagentic.eval.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T005 — the reference-text rendering, unit-tested with no runtime and no model. */
class ReferenceTextTest:

  @Test
  def rendersPassagesInOrderWithIndexAndSource(): Unit =
    val rendered = ReferenceText.render(
      List(
        "durability-tasks" -> "The runtime persists the task and the agent's process state.",
        "cap-4-session-memory" -> "History is replayed from the session memory entity."
      )
    )
    assertThat(rendered).isEqualTo(
      "[1] (durability-tasks) The runtime persists the task and the agent's process state.\n" +
        "[2] (cap-4-session-memory) History is replayed from the session memory entity."
    )

  /** Retrieval order is score order, and a judge reading "[1]" should be reading the best match —
    * so the rendering must not reorder. */
  @Test
  def preservesTheOrderItIsGiven(): Unit =
    val forwards = ReferenceText.render(List("a" -> "first", "b" -> "second"))
    val backwards = ReferenceText.render(List("b" -> "second", "a" -> "first"))
    assertThat(forwards).isNotEqualTo(backwards)
    assertThat(forwards).startsWith("[1] (a) first")
    assertThat(backwards).startsWith("[1] (b) second")

  @Test
  def rendersAnEmptyListAsTheEmptyString(): Unit =
    assertThat(ReferenceText.render(List.empty)).isEmpty()

  @Test
  def emptyIsTrueForAbsentAndWhitespaceOnlyText(): Unit =
    assertThat(ReferenceText.isEmpty("")).isTrue()
    assertThat(ReferenceText.isEmpty("   \n\t ")).isTrue()
    assertThat(ReferenceText.isEmpty(null)).isTrue()

  @Test
  def emptyIsFalseForRealReferenceMaterial(): Unit =
    assertThat(ReferenceText.isEmpty(ReferenceText.render(List("s" -> "text")))).isFalse()
