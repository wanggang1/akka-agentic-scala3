package com.gwgs.akkaagentic.compaction.probe

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import akka.javasdk.agent.SessionMemoryEntity
import akka.javasdk.annotations.{Component, Consume}
import akka.javasdk.consumer.Consumer
import org.slf4j.LoggerFactory

/** Phase 0 probe for feature 019 (R-1, R-2). NOT production.
  *
  * R-1: can a **Scala** consumer subscribe to the **runtime-owned** `SessionMemoryEntity` and match on its
  * `Event` hierarchy across the SDK's internal serializer? Capability 16 proved the analogous thing for
  * `TaskEntity`, but these events are records under a plain Java interface, not the same shape.
  *
  * R-2: `compactHistory` persists `HistoryCleared` + `UserMessageAdded` + `AiMessageAdded` (research S-6),
  * and `AiMessageAdded` is the event that carries `historySizeInBytes`. So a trigger keyed only on that
  * size could see **its own write** and compact for ever. This probe records the size reported on every
  * `AiMessageAdded` so the post-compaction value can be read off directly.
  */
@Component(id = "session-memory-probe-consumer")
@Consume.FromEventSourcedEntity(classOf[SessionMemoryEntity])
class SessionMemoryProbeConsumer extends Consumer:

  private val logger = LoggerFactory.getLogger(getClass)

  def onEvent(event: SessionMemoryEntity.Event): Consumer.Effect =
    val sessionId = messageContext().eventSubject().orElse("<none>")
    val seen = event match
      case e: SessionMemoryEntity.Event.UserMessageAdded =>
        s"UserMessageAdded(size=${e.sizeInBytes})"
      case e: SessionMemoryEntity.Event.AiMessageAdded =>
        SessionMemoryProbeConsumer.record(sessionId, e.historySizeInBytes)
        s"AiMessageAdded(size=${e.sizeInBytes}, HISTORY=${e.historySizeInBytes})"
      case e: SessionMemoryEntity.Event.ToolResponseMessageAdded =>
        s"ToolResponseMessageAdded(size=${e.sizeInBytes})"
      case _: SessionMemoryEntity.Event.HistoryCleared =>
        SessionMemoryProbeConsumer.cleared(sessionId)
        "HistoryCleared"
      case e: SessionMemoryEntity.Event.LimitedWindowSet =>
        s"LimitedWindowSet(max=${e.maxSizeInBytes})"
      case _: SessionMemoryEntity.Event.Deleted => "Deleted"
      case other                                => s"UNMATCHED(${other.getClass.getSimpleName})"
    logger.info("session-memory probe: [{}] {}", sessionId, seen)
    SessionMemoryProbeConsumer.log(sessionId, seen)
    effects().done()

object SessionMemoryProbeConsumer:
  private val entries = ConcurrentLinkedQueue[(String, String)]()
  private val sizes = ConcurrentLinkedQueue[(String, Long)]()
  private val clears = ConcurrentLinkedQueue[String]()

  private def log(sessionId: String, what: String): Unit = entries.add(sessionId -> what)
  private def record(sessionId: String, historySize: Long): Unit = sizes.add(sessionId -> historySize)
  private def cleared(sessionId: String): Unit = clears.add(sessionId)

  /** Every event kind observed for a session, in arrival order. */
  def seen(sessionId: String): List[String] =
    entries.asScala.collect { case (s, w) if s == sessionId => w }.toList

  /** `historySizeInBytes` as reported on each `AiMessageAdded`, in arrival order. */
  def historySizes(sessionId: String): List[Long] =
    sizes.asScala.collect { case (s, n) if s == sessionId => n }.toList

  def clearedCount(sessionId: String): Int = clears.asScala.count(_ == sessionId)
