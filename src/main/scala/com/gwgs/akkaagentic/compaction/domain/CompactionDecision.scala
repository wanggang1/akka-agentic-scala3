package com.gwgs.akkaagentic.compaction.domain

/** What to do about a session whose turn has just completed. */
enum Decision:
  /** Leave it alone — below the threshold, or compaction is switched off. Must cost nothing. */
  case Leave

  /** Summarise it. Carries the size that crossed the threshold, so the record can report it. */
  case Compact(historyBytes: Long)

/** The whole of the "should this be compacted?" rule, as one total function.
  *
  * It lives here rather than in the consumer so that the trigger holds no branching of its own and this
  * can be proven with no runtime. `Leave` is the overwhelmingly common answer and must stay free: no
  * entity read, no model call, no store write.
  */
object CompactionDecision:

  def decide(historyBytes: Long, threshold: CompactionThreshold): Decision =
    if !threshold.enabled then Decision.Leave
    else if historyBytes >= threshold.maxBytes then Decision.Compact(historyBytes)
    else Decision.Leave
