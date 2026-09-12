package com.gwgs.akkaagentic.streaming.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T006 — the streaming surface's input validation, unit-tested with no runtime and no model.
  *
  * Written before [[StreamQuestion]] exists, so it fails to compile first (Constitution III).
  *
  * The rule is deliberately the same as capability 8's, and deliberately **not** capability 8's code:
  * reusing `docs.domain.AskQuestion` would couple two capabilities for one non-blank check and put a
  * capability-14 edit one step away from capability 8's sources, which FR-009 forbids. Four lines
  * duplicated, pinned here.
  */
class StreamQuestionTest:

  /** The exact message the contract promises a `400` carries. Asserted as a value, not paraphrased,
    * so the endpoint and the contract cannot drift apart. */
  private val Expected = "question must not be blank"

  @Test
  def anAbsentQuestionIsRejected(): Unit =
    assertThat(StreamQuestion.validate(None)).isEqualTo(Left(Expected))

  @Test
  def anEmptyQuestionIsRejected(): Unit =
    assertThat(StreamQuestion.validate(Some(""))).isEqualTo(Left(Expected))

  /** Whitespace only — the case a caller hits with `{"message":"  "}`, and the one the endpoint test
    * drives over HTTP. */
  @Test
  def aWhitespaceOnlyQuestionIsRejected(): Unit =
    assertThat(StreamQuestion.validate(Some("   "))).isEqualTo(Left(Expected))
    assertThat(StreamQuestion.validate(Some("\t\n "))).isEqualTo(Left(Expected))

  /** Parse, don't validate: what comes back carries the question already trimmed, so nothing
    * downstream trims, re-checks, or calls `.get`. */
  @Test
  def aValidQuestionIsTrimmedAndCarriedInTheResult(): Unit =
    assertThat(StreamQuestion.validate(Some("  why does it stream?  ")))
      .isEqualTo(Right(StreamQuestion("why does it stream?")))

  /** Inner whitespace is content, not padding — only the ends are trimmed. */
  @Test
  def innerWhitespaceIsPreserved(): Unit =
    val result = StreamQuestion.validate(Some("why  does   it stream?"))
    assertThat(result).isEqualTo(Right(StreamQuestion("why  does   it stream?")))
