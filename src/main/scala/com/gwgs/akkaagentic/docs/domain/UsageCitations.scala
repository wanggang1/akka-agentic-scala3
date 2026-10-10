package com.gwgs.akkaagentic.docs.domain

/** The citation-honesty rule for capability 21 (fork B4, feature 023), kept framework-free so it is
  * provable in isolation (constitution §II/§III).
  *
  * Capability 8 cites every passage it **retrieved**; this capability cites only the ones the model
  * reports **using**. But a model that self-reports its sources is unreliable (capability 7's D6 finding,
  * the exact thing cap-8 was built to avoid), so self-report is never trusted on its own: a reported
  * label is cited only if it was genuinely among the retrieved set. That intersection is the **honesty
  * floor** — self-report can only ever *narrow* the ground-truth retrieved set, never invent a citation.
  * Every label this returns was provably offered to the model as context (SC-002).
  */
object UsageCitations:

  /** Select the usage-accurate citations.
    *
    * @param reportedUsed    the model's self-reported used-source labels (untrusted)
    * @param retrievedLabels the labels actually offered as context, de-duplicated, in retrieval order
    * @param declined        true iff the answer was the decline sentinel
    * @return the retrieved labels the model also reported using, in retrieval order, de-duplicated;
    *         empty on a decline (FR-005), and empty when the intersection is empty on a non-decline
    *         answer (FR-007 — cite nothing, never fall back to cap-8's full retrieved set)
    */
  def select(
      reportedUsed: List[String],
      retrievedLabels: List[String],
      declined: Boolean
  ): List[String] =
    if declined then Nil
    else
      val used = reportedUsed.toSet
      retrievedLabels.distinct.filter(used.contains)
