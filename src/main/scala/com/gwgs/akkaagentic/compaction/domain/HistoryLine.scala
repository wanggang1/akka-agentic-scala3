package com.gwgs.akkaagentic.compaction.domain

/** One line of a conversation, in terms this layer can reason about.
  *
  * ==Why this type exists at all==
  * The history the SDK hands us is a list of `akka.javasdk.agent.SessionMessage`, and the constitution's
  * Principle II (and AGENTS.md's package rules) put **no Akka dependency in `domain`**. So the formatting
  * rule — which is the part worth unit-testing, because it decides what survives a compaction — works on
  * this neutral type, and the application layer maps `SessionMessage` onto it at the boundary.
  *
  * *(This is a deliberate departure from tasks.md T005, which said `SummaryRequest` would take
  * `SessionMessage` directly. That would have dragged the SDK into the domain to save one small mapper,
  * against a principle this feature's own plan.md signed off.)*
  */
enum HistoryLine:
  case User(text: String)
  case Ai(text: String, toolCalls: List[HistoryLine.ToolCall])
  case ToolResult(name: String, text: String)

object HistoryLine:
  /** A tool the assistant asked for, reduced to what a summary needs to keep. */
  final case class ToolCall(name: String, arguments: String)
