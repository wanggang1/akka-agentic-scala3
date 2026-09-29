package com.gwgs.akkaagentic.compaction.domain

import java.time.Instant

import scala.jdk.CollectionConverters.*

import akka.javasdk.agent.SessionMessage
import com.gwgs.akkaagentic.compaction.application.Compactor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T020 / User Story 2 — the property the integration test depends on: **a tool's outcome is recoverable
  * from the summariser's input text alone.**
  *
  * This runs over **real `SessionMessage` values**, not hand-made domain ones, because the risk is not the
  * formatting alone — it is the pair of steps: `Compactor.lineOf` mapping the SDK's messages onto
  * [[HistoryLine]], and [[SummaryRequest]] rendering those. Either could silently drop what a tool did,
  * and the compacted history has nowhere else to keep it: after compaction the session holds two prose
  * messages, so anything not in this text is gone for good.
  *
  * No runtime and no model — this is the half of FR-002 that can be proven outright. Whether a *model*
  * then carries it into the summary is a live claim, labelled in `SummaryContentIntegrationTest`.
  */
class SummaryRequestToolProseTest:

  private val t0 = Instant.parse("2026-09-29T09:00:00Z")

  /** A tool-using turn as the SDK actually stores it: the assistant asks, the tool answers, the assistant
    * concludes. */
  private val toolUsingHistory: List[SessionMessage] = List(
    SessionMessage.UserMessage(t0, "add a to-do to buy milk", "personal-assistant-agent"),
    SessionMessage.AiMessage(
      t0.plusSeconds(1),
      "",
      "personal-assistant-agent",
      List(SessionMessage.ToolCallRequest("call-1", "addTodo", """{"description":"buy milk"}""")).asJava),
    SessionMessage.ToolCallResponse(
      t0.plusSeconds(2),
      "personal-assistant-agent",
      "call-1",
      "addTodo",
      "Added \"buy milk\" as item 1."),
    SessionMessage.AiMessage(t0.plusSeconds(3), "Added \"buy milk\" as item 1.", "personal-assistant-agent")
  )

  private def render(history: List[SessionMessage]): String =
    SummaryRequest.from(history.map(Compactor.lineOf)).text

  @Test
  def whatTheToolWasAskedForSurvivesTheMapping(): Unit =
    val text = render(toolUsingHistory)
    assertThat(text).contains("TOOL_CALL_REQUEST: name=addTodo")
    assertThat(text).contains("buy milk")

  /** The important half: what the tool RETURNED. A summary that kept the request and lost the result would
    * describe an intention rather than a fact. */
  @Test
  def whatTheToolReturnedSurvivesTheMapping(): Unit =
    val text = render(toolUsingHistory)
    assertThat(text).contains("TOOL_CALL_RESPONSE (addTodo)")
    assertThat(text).contains("Added \"buy milk\" as item 1.")

  @Test
  def theOrderOfTheExchangeIsPreserved(): Unit =
    val text = render(toolUsingHistory)
    assertThat(text.indexOf("add a to-do to buy milk")).isLessThan(text.indexOf("TOOL_CALL_REQUEST"))
    assertThat(text.indexOf("TOOL_CALL_REQUEST")).isLessThan(text.indexOf("TOOL_CALL_RESPONSE"))

  /** An AI turn carrying a tool call usually has EMPTY text — the words come later. If the mapping kept
    * only `text`, a tool-using turn would render as nothing at all. */
  @Test
  def anAiTurnWithNoTextButAToolCallStillRendersItsCall(): Unit =
    val line = Compactor.lineOf(toolUsingHistory(1))
    line match
      case HistoryLine.Ai(text, calls) =>
        assertThat(text).isEmpty()
        assertThat(calls.map(_.name).mkString).isEqualTo("addTodo")
      case other => throw AssertionError(s"expected an Ai line, got $other")

  @Test
  def eachSdkMessageTypeMapsToItsOwnLineType(): Unit =
    assertThat(Compactor.lineOf(toolUsingHistory.head).getClass.getSimpleName).isEqualTo("User")
    assertThat(Compactor.lineOf(toolUsingHistory(1)).getClass.getSimpleName).isEqualTo("Ai")
    assertThat(Compactor.lineOf(toolUsingHistory(2)).getClass.getSimpleName).isEqualTo("ToolResult")

  /** The whole exchange reduced to prose still lets a reader answer "what did the tool do?" — which is the
    * question FR-002 exists to keep answerable after the turns themselves are gone. */
  @Test
  def theOutcomeIsRecoverableFromTheTextAlone(): Unit =
    val text = render(toolUsingHistory)
    assertThat(text).doesNotContain("ToolCallRequest(") // no toString leakage of SDK types
    assertThat(text.linesIterator.exists(l => l.contains("Added") && l.contains("buy milk"))).isTrue()
