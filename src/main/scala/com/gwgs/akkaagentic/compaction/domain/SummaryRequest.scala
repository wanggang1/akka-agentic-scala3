package com.gwgs.akkaagentic.compaction.domain

/** The single text a summariser reads, plus how many messages it stands for.
  *
  * This is where FR-002 is actually satisfied. A tool call and its response are rendered as **readable
  * lines**, so a summary can carry what a tool *did* without carrying a structured tool call — which is
  * the whole reason compaction is safe where `readLast(N)` was not. After compaction the history is two
  * prose messages; there is no request/response pair left for anything to orphan.
  *
  * Pure and total: an empty history yields an empty request rather than throwing, because a session can be
  * compacted only if it has messages, and a formatter is the wrong place to enforce that.
  */
final case class SummaryRequest(text: String, messageCount: Int)

object SummaryRequest:

  def from(lines: List[HistoryLine]): SummaryRequest =
    SummaryRequest(lines.map(render).mkString("\n\n"), lines.size)

  private def render(line: HistoryLine): String = line match
    case HistoryLine.User(text) => s"USER:\n$text"
    case HistoryLine.Ai(text, toolCalls) =>
      val calls = toolCalls.map(c => s"\n\tTOOL_CALL_REQUEST: name=${c.name}, args=${c.arguments}")
      s"AI:\n$text${calls.mkString}"
    case HistoryLine.ToolResult(name, text) => s"TOOL_CALL_RESPONSE ($name):\n$text"
