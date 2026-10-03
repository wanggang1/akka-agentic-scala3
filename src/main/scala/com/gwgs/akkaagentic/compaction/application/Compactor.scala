package com.gwgs.akkaagentic.compaction.application

import scala.jdk.CollectionConverters.*
import scala.util.Try

import akka.javasdk.agent.{SessionHistory, SessionMessage}
import com.gwgs.akkaagentic.compaction.domain.{
  CompactionDecision,
  CompactionThreshold,
  Decision,
  HistoryLine,
  Outcome,
  SummaryRequest
}

/** What one compaction attempt did, with every number read back rather than predicted. */
final case class CompactionResult(
    outcome: Outcome,
    bytesBefore: Long,
    bytesAfter: Long,
    messagesReplaced: Int
)

/** Orchestrates one compaction: read, render, summarise, replace, **read back**, decide.
  *
  * All of the judgement lives here, in Scala, and [[SessionMemoryGateway]] holds only the three method
  * references the SDK forces into Java. That split was not planned — the first draft put this logic in the
  * Java class and javac refused with *"enum classes may not be instantiated"*, because a Java caller
  * cannot construct a Scala 3 `enum` case. The compiler pushed the design the right way: the quarantine is
  * now one class with no decisions in it.
  *
  * ==The event's size is a HINT; the history is the authority==
  * Measured while implementing US1: a burst of turns crossing the threshold produces a burst of events,
  * each carrying the size *at the time it was written*. By the time the consumer acts on the third, the
  * first has already compacted the session — so acting on the event's number alone spent three model calls
  * to do one compaction, and the platform silently discarded two of them (research R-4's guard doing its
  * job). So the size is re-checked against the history actually read, and an attempt that is no longer
  * needed returns `None` before any model is called. The event tells us *to look*; the history tells us
  * *whether*.
  *
  * ==The re-read is the whole point==
  * Research R-4 measured that `compactHistory` accepts a stale `sequenceNumber` **silently** — no
  * exception, no result, the history simply unchanged. So "we asked for a compaction" and "a compaction
  * happened" are different facts, and the only way to tell them apart is to ask the entity what stands
  * now. Without it this class would report a bound that was never applied.
  */
final class Compactor(gateway: SessionMemoryGateway):

  /** @return `None` when a second look says no compaction is needed — nothing was done and nothing should
    *         be recorded. `Some` when an attempt was made, whatever its outcome.
    */
  def compact(sessionId: String, threshold: CompactionThreshold, bytesHint: Long): Option[CompactionResult] =
    val before = gateway.history(sessionId)
    val messages = before.messages.asScala.toList
    val actualBytes = Compactor.sizeOf(before)

    // The event's number may be stale — another attempt may already have compacted this session.
    CompactionDecision.decide(actualBytes, threshold) match
      case Decision.Leave => None
      case Decision.Compact(_) if messages.isEmpty =>
        Some(failed(actualBytes, "history was empty"))
      case Decision.Compact(_) =>
        val request = SummaryRequest.from(messages.map(Compactor.lineOf))
        Try(gateway.summarize(sessionId, request.text)).toEither match
          case Left(error) =>
            Some(failed(actualBytes, s"summariser call failed: ${error.getClass.getSimpleName}"))
          case Right(reply) =>
            val summary = reply.value
            if summary == null || !summary.isUsable then
              Some(failed(actualBytes, "summariser returned no usable summary"))
            else Some(replace(sessionId, before, summary, reply.tokenUsage, actualBytes, messages.size))

  private def replace(
      sessionId: String,
      before: SessionHistory,
      summary: ConversationSummary,
      usage: akka.javasdk.agent.Agent.TokenUsage,
      bytesBefore: Long,
      messageCount: Int
  ): CompactionResult =
    val written = Try(
      gateway.replace(
        sessionId,
        Compactor.ComponentId,
        summary.userMessage,
        summary.aiMessage,
        usage.inputTokens,
        usage.outputTokens,
        before.sequenceNumber))
    if written.isFailure then
      failed(bytesBefore, s"replacing the history failed: ${written.failed.get.getClass.getSimpleName}")
    else
      // R-4: the write reported nothing. Ask what actually stands now.
      //
      // The discriminator is whether OUR summary is at the head of the history, not whether the message
      // count fell. A concurrent turn can append while we work, which made a count comparison report a
      // successful compaction as skipped (measured in US1). The head is unambiguous: `compactHistory`
      // clears and re-adds, so if our summary landed it is first, and later turns append after it.
      val after = gateway.history(sessionId)
      val bytesAfter = Compactor.sizeOf(after)
      val landed = after.messages.asScala.headOption.exists {
        case user: SessionMessage.UserMessage =>
          user.componentId == Compactor.ComponentId && user.text == summary.userMessage
        case _ => false
      }
      if landed then CompactionResult(Outcome.Compacted, bytesBefore, bytesAfter, messageCount)
      else CompactionResult(Outcome.SkippedStale, bytesBefore, bytesAfter, 0)

  private def failed(bytesBefore: Long, reason: String): CompactionResult =
    CompactionResult(Outcome.Failed(reason), bytesBefore, bytesBefore, 0)

object Compactor:

  /** The id the summary's messages are attributed to. `compactHistory` requires both halves to agree. */
  val ComponentId = "compaction-agent"

  def sizeOf(history: SessionHistory): Long =
    history.messages.asScala.foldLeft(0L)((total, message) => total + message.size)

  /** The SDK's messages onto the neutral domain type, so the formatting rule stays Akka-free. */
  def lineOf(message: SessionMessage): HistoryLine = message match
    case user: SessionMessage.UserMessage => HistoryLine.User(user.text)
    case ai: SessionMessage.AiMessage =>
      HistoryLine.Ai(
        ai.text,
        ai.toolCallRequests.asScala.toList.map(r => HistoryLine.ToolCall(r.name, r.arguments)))
    case tool: SessionMessage.ToolCallResponse => HistoryLine.ToolResult(tool.name, tool.text)
    case other                                 => HistoryLine.User(String.valueOf(other))
