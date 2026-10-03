package com.gwgs.akkaagentic.compaction.domain

/** How large a session's stored history may get before it is summarised, and whether that happens at all.
  *
  * Constructed only through [[CompactionThreshold.of]], which returns an `Either` rather than throwing —
  * "parse, don't validate", so anything holding a `CompactionThreshold` holds one already proven in range.
  * Translating a `Left` into the right kind of failure is the application layer's job
  * ([[com.gwgs.akkaagentic.compaction.application.CompactionSettings]]), because only it knows that a bad
  * value is the *server's* fault.
  *
  * ==Why the ceiling is 256 KiB, and why it is the load-bearing part==
  * Measured in specs/019 research S-1: the SDK **already** bounds session history at
  * `akka.javasdk.agent.memory.limited-window.max-size`, whose default is 510 KiB and which the SDK
  * documents as the *maximum permitted* value. That bound evicts oldest-first. So a threshold at or near
  * 510 KiB would let the SDK discard the earliest turns **before** compaction ever ran — compaction would
  * look like it worked while summarising a history whose beginning was already thrown away. The floor
  * (1 KiB) only keeps tests and typos honest; the ceiling is what makes the capability mean anything.
  */
final case class CompactionThreshold private (maxBytes: Long, enabled: Boolean)

object CompactionThreshold:

  /** Below this a threshold is pointless — a single turn can exceed it. */
  val MinBytes: Long = 1024L

  /** Above this the SDK's own eviction gets there first; see the class comment. */
  val MaxBytes: Long = 256L * 1024L

  /** The SDK's own history bound, recorded here because it is the reason [[MaxBytes]] exists.
    * Not enforced by us — the runtime applies it whether we like it or not. */
  val SdkEvictionBytes: Long = 510L * 1024L

  def of(maxBytes: Long, enabled: Boolean): Either[String, CompactionThreshold] =
    if maxBytes < MinBytes || maxBytes > MaxBytes then
      Left(s"must be between $MinBytes and $MaxBytes bytes, was $maxBytes")
    else Right(CompactionThreshold(maxBytes, enabled))
