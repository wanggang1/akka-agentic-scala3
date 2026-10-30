package com.gwgs.akkaagentic.docs.domain

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** Capability 22 (fork B5): the incremental citation-marker stripper.
  *
  * A streamed turn has **no structured output channel** (research R1), so the only signal for which
  * sources the model used is the inline `[n]` marker it writes into the prose. This value pulls those
  * markers out of the text **as it streams**, chunk by chunk, so the caller reads clean prose while the
  * numbers accumulate for the terminal `citations` frame (research R2/R3).
  *
  * **No Akka import** (Constitution II, domain independence): a pure, immutable function of
  * `(State, chunk)`, unit-testable with no runtime. The endpoint — Java by force of the `tokenStream`
  * method-reference wall (README §16) — drives it from a stream `statefulMap`, holding the [[State]]
  * carry between elements and calling [[finish]] at completion. The result types are deliberately
  * Java-shaped (`java.util.List[Integer]`) because that Java endpoint is the sole caller and
  * [[GroundedCitedPrep]] consumes the same list type; the stripping logic itself stays idiomatic Scala
  * (immutable, tail-recursive, no `var`).
  *
  * **Marker grammar**: `[` + one or more ASCII digits + `]`. Non-numeric brackets (`[note]`, `[]`,
  * `[12x`) are left as literal text — only numeric markers are the citation signal, so the stripper and
  * the agent prompt agree on exactly one shape.
  *
  * **Boundary property** (the one that matters, and the offline test oracle): for *any* way a full
  * answer is split into chunks, the concatenation of all `emit`s (every [[step]] then [[finish]]) equals
  * the answer with every numeric `[n]` removed, and the concatenation of all `numbers` equals the
  * markers' numbers in order. A marker split across a chunk boundary (`"…restart [1"` + `"2] …"`) must
  * never leak its opening fragment.
  */
object CitationMarkers:

  /** The carry: the trailing text tail that might begin a `[n]` marker, held across chunks (always of
    * the shape `[` + zero-or-more digits, with no closing `]` yet). Opaque — constructed only by
    * [[empty]] and [[step]]; the caller treats it as a token to thread through `statefulMap`.
    */
  final case class State private[CitationMarkers] (pending: String)

  /** The empty carry — the initial `statefulMap` state. */
  val empty: State = State("")

  /** Result of [[step]]: the clean text to emit now, the carry to thread into the next step, and the
    * numbers of any markers completed in this chunk (in order). */
  final case class StepResult(emit: String, carry: State, numbers: java.util.List[Integer])

  /** Result of [[finish]]: the clean text flushed from the carry, and any markers completed in it. */
  final case class FinishResult(emit: String, numbers: java.util.List[Integer])

  /** Consume one chunk. Appends it to the carry, extracts every **complete** `[n]` marker (recording
    * its number, removing it from the text), emits the safe prefix, and retains from the last unmatched
    * `[` onward — which might complete into a marker in the next chunk. */
  def step(state: State, chunk: String): StepResult =
    val (emit, carry, numbers) = scan(state.pending + chunk, Vector.empty, Vector.empty)
    StepResult(emit.mkString, State(carry), boxed(numbers))

  /** Flush the carry at end of stream: no more input can arrive, so a dangling `[…` that never closed is
    * emitted as literal text (it was not a marker). A complete marker left in the tail is still
    * extracted. */
  def finish(state: State): FinishResult =
    val (emit, carry, numbers) = scan(state.pending, Vector.empty, Vector.empty)
    FinishResult((emit :+ carry).mkString, boxed(numbers))

  /** Scan `rest` left to right: emit safe text, extract complete numeric markers, and return any
    * trailing `[…` fragment as the carry. Tail-recursive, immutable accumulators. */
  @tailrec
  private def scan(
      rest: String,
      emit: Vector[String],
      numbers: Vector[Int]
  ): (Vector[String], String, Vector[Int]) =
    val i = rest.indexOf('[')
    if i < 0 then
      // No bracket anywhere: all of `rest` is safe, nothing to carry.
      (emit :+ rest, "", numbers)
    else
      val before = rest.substring(0, i)
      val fromBracket = rest.substring(i) // starts with '['
      val digits = fromBracket.substring(1).takeWhile(_.isDigit)
      val afterDigits = 1 + digits.length
      if fromBracket.length == afterDigits then
        // Buffer ends with "[<digits>" and no closing ']': it might complete next chunk → carry it.
        (emit :+ before, fromBracket, numbers)
      else
        val number = if digits.nonEmpty then digits.toIntOption else None
        if fromBracket.charAt(afterDigits) == ']' && number.isDefined then
          // Complete numeric marker: record its number, drop it, continue after the ']'.
          scan(fromBracket.substring(afterDigits + 1), emit :+ before, numbers :+ number.get)
        else
          // Not a numeric marker (`[` then non-digit, `[]`, `[123x`, or an overflowing run): the '['
          // is literal. Emit through it and rescan from the next char so following text is re-examined.
          scan(fromBracket.substring(1), emit :+ before :+ "[", numbers)

  private def boxed(numbers: Vector[Int]): java.util.List[Integer] =
    numbers.map(Int.box).asJava
