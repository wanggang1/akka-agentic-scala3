package com.gwgs.akkaagentic.compaction.application

import akka.javasdk.agent.{Agent, MemoryProvider}
import akka.javasdk.annotations.Component
import org.slf4j.LoggerFactory

/** Turns many turns of a conversation into one user message and one AI message of prose.
  *
  * This is the only model call in the capability, and it is off the user's turn: the trigger runs after a
  * turn has already been answered, so a slow summariser costs nothing a caller can feel.
  *
  * ==Two deliberate choices==
  *  1. **`MemoryProvider.none()`.** A summariser that kept session memory would write its own turns into
  *     session history — feeding compaction into the very thing it compacts, and growing the history it
  *     was asked to shrink. The SDK's own judges disable memory for a related reason (cap-13).
  *  2. **`.onFailure` returns an unusable summary rather than throwing.** FR-005 requires that a failed
  *     compaction leave the history exactly as it was and never break a user's turn, so failure has to
  *     travel as a *value* the caller can inspect. An empty summary is rejected by
  *     [[ConversationSummary.isUsable]] and recorded as `Failed`, which is the same sentinel technique
  *     capabilities 8, 12 and 13 use — and the reason it works here is that nothing downstream writes an
  *     unusable summary.
  */
@Component(id = "compaction-agent")
class CompactionAgent extends Agent:
  import CompactionAgent.*

  def summarize(conversation: String): Agent.Effect[ConversationSummary] =
    effects()
      .systemMessage(SystemMessage)
      .memory(MemoryProvider.none())
      .userMessage(conversation)
      .responseConformsTo(classOf[ConversationSummary])
      .onFailure { error =>
        logger.warn("compaction summary failed; the history will be left as it is", error)
        Unusable
      }
      .thenReply()

object CompactionAgent:

  private val logger = LoggerFactory.getLogger(classOf[CompactionAgent])

  /** What `onFailure` yields. `isUsable` is false, so the caller records `Failed` and writes nothing. */
  val Unusable: ConversationSummary = ConversationSummary("", "")

  private val SystemMessage =
    """You compress an interaction history into exactly two messages: one user message and one
      |assistant message.
      |
      |The history you are given uses these markers:
      |  USER:                     what the person said
      |  AI:                       what the assistant answered
      |  TOOL_CALL_REQUEST:        a tool the assistant asked for, with its arguments
      |  TOOL_CALL_RESPONSE (x):   what that tool returned
      |
      |Rules:
      |  - Keep every fact the person established about themselves or their intent.
      |  - Keep the OUTCOME of each tool call — what it actually did or found — as ordinary prose.
      |    Do NOT reproduce tool calls as structured calls; describe their results in words.
      |  - Keep the original voice: the user message should read as the person, the assistant message
      |    as the assistant.
      |  - Be brief. You are replacing a long history, not restating it.
      |
      |Respond with the two messages and nothing else.""".stripMargin
