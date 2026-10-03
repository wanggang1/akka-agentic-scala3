package com.gwgs.akkaagentic.compaction.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T010 — what a summariser is given to read, which decides what can survive a compaction. */
class SummaryRequestTest:

  @Test
  def turnsAreRenderedInOrder(): Unit =
    val request = SummaryRequest.from(
      List(
        HistoryLine.User("my name is Ada"),
        HistoryLine.Ai("Nice to meet you, Ada!", Nil),
        HistoryLine.User("what is my name?"),
        HistoryLine.Ai("Your name is Ada.", Nil)))
    assertThat(request.messageCount).isEqualTo(4)
    assertThat(request.text.indexOf("my name is Ada")).isLessThan(request.text.indexOf("what is my name?"))
    assertThat(request.text).contains("USER:").contains("AI:")

  /** FR-002: the substance of a tool call has to reach the summary as **prose**, because the compacted
    * history is two plain messages — there is no structured tool call for it to live in. */
  @Test
  def aToolCallAndItsResponseBothBecomeReadableText(): Unit =
    val request = SummaryRequest.from(
      List(
        HistoryLine.User("add a to-do to buy milk"),
        HistoryLine.Ai("", List(HistoryLine.ToolCall("addTodo", """{"description":"buy milk"}"""))),
        HistoryLine.ToolResult("addTodo", "Added \"buy milk\" as item 1."),
        HistoryLine.Ai("Added \"buy milk\" as item 1.", Nil)))
    assertThat(request.text).contains("TOOL_CALL_REQUEST: name=addTodo")
    assertThat(request.text).contains("buy milk")
    assertThat(request.text).contains("TOOL_CALL_RESPONSE (addTodo)")
    assertThat(request.text).contains("Added \"buy milk\" as item 1.")

  @Test
  def anEmptyHistoryYieldsAnEmptyRequestRatherThanThrowing(): Unit =
    val request = SummaryRequest.from(Nil)
    assertThat(request.messageCount).isEqualTo(0)
    assertThat(request.text).isEmpty()

  @Test
  def anAiTurnWithSeveralToolCallsKeepsThemAll(): Unit =
    val request = SummaryRequest.from(
      List(HistoryLine.Ai("working on it", List(HistoryLine.ToolCall("a", "{}"), HistoryLine.ToolCall("b", "{}")))))
    assertThat(request.text).contains("name=a").contains("name=b")
