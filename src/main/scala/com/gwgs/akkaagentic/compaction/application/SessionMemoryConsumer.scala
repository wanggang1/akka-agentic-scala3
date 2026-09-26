package com.gwgs.akkaagentic.compaction.application

import akka.javasdk.agent.SessionMemoryEntity
import akka.javasdk.annotations.{Component, Consume}
import akka.javasdk.client.ComponentClient
import akka.javasdk.consumer.Consumer
import com.gwgs.akkaagentic.compaction.domain.{CompactionDecision, Decision}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/** The trigger. Nothing calls it: it watches every session's memory and acts when one gets large.
  *
  * ==Why `AiMessageAdded`, and only that==
  * Research S-5 measured that `historySizeInBytes` is carried on **`AiMessageAdded` alone** — no other
  * event has it. Two consequences, both good:
  *   - the common case (below the threshold) costs **no entity read at all**, because the size arrives on
  *     the event;
  *   - the trigger structurally **cannot fire mid-turn**, i.e. never between a tool call and its response,
  *     because that event is what ends a turn.
  *
  * ==Handler selection is by PARAMETER TYPE==
  * A `Consumer` declares no abstract handler; the SDK's router holds a map from payload type to method
  * (capability 16). So this method's *name* is irrelevant and its *parameter type* is everything. Two
  * handlers taking the same type stop the service starting; a handler taking the wrong type compiles,
  * starts, and is silently never called — the dangerous one, whose only symptom is an integration test
  * timing out.
  *
  * ==Why there is no loop guard==
  * `compactHistory` persists `HistoryCleared` + `UserMessageAdded` + `AiMessageAdded` (research S-6), so
  * this consumer does see its own write. Research R-2 measured that the size reported on that event is
  * computed **after** the clear — 22 bytes against 16060 before — so the decision returns `Leave` and no
  * loop forms. That is the SDK's ordering rather than our property, so a test pins it instead of a comment
  * asserting it.
  */
@Component(id = "session-memory-consumer")
@Consume.FromEventSourcedEntity(classOf[SessionMemoryEntity])
class SessionMemoryConsumer(componentClient: ComponentClient, config: Config) extends Consumer:

  private val logger = LoggerFactory.getLogger(getClass)
  private val threshold = CompactionSettings.threshold(config)
  private val compactor = new Compactor(new SessionMemoryGateway(componentClient))
  CompactionStore.configure(config)

  def onEvent(event: SessionMemoryEntity.Event): Consumer.Effect =
    event match
      case added: SessionMemoryEntity.Event.AiMessageAdded =>
        CompactionDecision.decide(added.historySizeInBytes, threshold) match
          case Decision.Leave => effects().done()
          case Decision.Compact(bytes) => compact(bytes)
      case _ =>
        // Every other event is someone else's turn being recorded. Nothing to decide.
        effects().done()

  private def compact(bytesBefore: Long): Consumer.Effect =
    val sessionId = messageContext().eventSubject().orElse("")
    if sessionId.isEmpty then
      logger.warn("compaction skipped: the event carried no session id")
      effects().done()
    else
      compactor.compact(sessionId, threshold, bytesBefore) match
        case None =>
          // A second look at the real history said no: another attempt in this burst already compacted
          // the session, and the size on this event was stale. Nothing was done, so nothing is recorded.
          logger.debug("compaction for [{}] no longer needed; the event's size was stale", sessionId)
        case Some(result) =>
          CompactionStore.record(
            sessionId,
            result.bytesBefore,
            result.bytesAfter,
            result.messagesReplaced,
            result.outcome)
          logger.info(
            "compaction for [{}]: {} ({} -> {} bytes, {} messages replaced)",
            sessionId,
            result.outcome,
            result.bytesBefore,
            result.bytesAfter,
            result.messagesReplaced)
      // Always `done()`. A failed compaction must not fail the delivery: redelivery is unbounded and
      // blocks every other session behind it (capability 16's measured failure contract), and the user's
      // turn has already been answered — there is nothing left to save by retrying.
      effects().done()
