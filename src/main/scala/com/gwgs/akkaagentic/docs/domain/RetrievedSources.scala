package com.gwgs.akkaagentic.docs.domain

/** The retrieved passages' sources, in the exact order and multiplicity the model is shown them, and the
  * single authority on the 1-based numbering (`[n]`) the model reports against. Kept framework-free so it
  * is provable in isolation (constitution §II/§III).
  *
  * Capability 8 cites every passage it **retrieved**; capability 21 (fork B4) cites only the ones the
  * model reports **using**. The original B4 contract had the model echo each used source's *label* string
  * into `usedSources`; live measurement on `qwen3:8b` showed the model reliably knows which source it used
  * (it emits an inline `[n]` marker) but inconsistently *mirrors* the label into the structured field
  * (research R6 — the under-report is a population failure, not a mis-identification). So this contract has
  * the model report the **number** `[n]` instead, which it does reliably, and the mapping back to a label
  * lives here.
  *
  * A model that self-reports is still untrusted (capability 7's D6 finding). The **honesty floor** is now
  * *structural*: a reported index resolves to a citation only via [[label]]/[[cite]], which look it up in
  * the presented set — an out-of-range or fabricated index resolves to nothing and cannot invent a
  * citation (SC-002). [[SourceIndex]] and [[Label]] are opaque (newtypes): a raw `Int` index or raw
  * `String` label cannot enter the citation logic without going through [[of]], making the
  * parse-don't-validate boundary nominal rather than conventional.
  */
final case class RetrievedSources private (private val labels: List[RetrievedSources.Label]):
  import RetrievedSources.*

  /** The 1-based numbers the sources are presented as — the values the model reports in `usedSources`. */
  def indexes: List[SourceIndex] = labels.indices.map(i => SourceIndex(i + 1)).toList

  /** The label for a reported 1-based index, or `None` if it is not one this set actually presented (the
    * honesty floor: an out-of-range or fabricated index resolves to nothing). */
  def label(index: SourceIndex): Option[Label] = labels.lift(index.value - 1)

  /** The usage-accurate citations: the labels for the reported indices this set knows, in retrieval
    * order, de-duplicated; empty on a decline (FR-005) and empty when nothing verifiable was reported
    * (FR-007 — cite nothing, never fall back to cap-8's full retrieved set). Every label returned was
    * provably presented to the model (SC-002). */
  def cite(reportedIndexes: List[SourceIndex], declined: Boolean): List[Label] =
    if declined then Nil
    else
      val reported = reportedIndexes.toSet
      labels.zipWithIndex.collect { case (l, i) if reported.contains(SourceIndex(i + 1)) => l }.distinct

object RetrievedSources:

  /** A 1-based position in the presented source list — the number the model is shown as `[n]` and the
    * only thing it reports back. An opaque newtype over `Int`: a raw `Int` cannot be passed where an
    * index is expected (erased to `Int` at runtime, zero cost). */
  opaque type SourceIndex = Int
  object SourceIndex:
    def apply(n: Int): SourceIndex = n
    extension (i: SourceIndex) def value: Int = i

  /** A source label (e.g. a doc id). An opaque newtype over `String` so a raw label string cannot leak
    * into the citation logic without going through [[of]]. */
  opaque type Label = String
  object Label:
    def apply(s: String): Label = s
    extension (l: Label) def value: String = l

  /** Build from the source labels in the exact order and multiplicity the model is shown them. **No
    * de-duplication** — index `n` must line up with the n-th presented passage, so a source that appears
    * in two passages occupies two indexes. (De-duplication happens only in [[cite]]'s output.) Zero-cost:
    * inside this scope `List[String]` and `List[Label]` are the same type. */
  def of(labels: List[String]): RetrievedSources = RetrievedSources(labels)
