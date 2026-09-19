package com.gwgs.akkaagentic.feed.probe

import scala.jdk.CollectionConverters.*

import akka.javasdk.annotations.{Component, Consume, DeleteHandler}
import akka.javasdk.consumer.Consumer
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList
import org.slf4j.LoggerFactory

/** Q-A / Q-B / Q-D / Q-E probe: a **Scala** consumer of capability 6's Java `TodoEntity`.
  *
  * A plain top-level class — the shape capability 11 had to reach for its View updater by moving it into
  * a companion object. Whether a Consumer has the same bytecode-shape constraint is part of Q-A.
  *
  * It records every delivery, including the runtime class of what arrived (Q-B) and the metadata keys
  * (does anything identify a delivery or an attempt? — Q-D/Q-E). For a subject the test has poisoned it
  * throws, until `giveUpAfter` attempts, after which it returns `done()` — the self-bounded exit Q-D asks
  * about.
  */
@Component(id = "todo-probe-consumer")
@Consume.FromKeyValueEntity(classOf[TodoEntity])
class TodoProbeConsumer extends Consumer:
  private val logger = LoggerFactory.getLogger(classOf[TodoProbeConsumer])

  def onUpdate(list: TodoList): Consumer.Effect =
    val subject = messageContext().eventSubject().toScala
    val detail = s"${list.getClass.getName}: ${list.todos().asScala.map(t => s"${t.id}:${t.description}:${t.completed}").mkString("[", ",", "]")} next=${list.nextId()}"
    // Keys, plus the VALUE of ce-id: whether a redelivery carries the same id decides whether it can key
    // idempotence (Q-E).
    val keys = messageContext().metadata().asScala.map(e => if e.getKey == "ce-id" then s"ce-id=${e.getValue}" else e.getKey).toList.sorted
    val seen = ProbeLog.record("todo", subject, "update", detail, keys)
    logger.info("todo probe: delivery for [{}] attempt {}: {}", subject.getOrElse("?"), seen.attempt, detail)
    val switches = ProbeLog.switches
    if subject.exists(switches.poisoned) && seen.attempt < switches.giveUpAfter then
      logger.info("todo probe: failing delivery for [{}], attempt {}", subject.getOrElse("?"), seen.attempt)
      throw RuntimeException(s"probe: poisoned subject ${subject.getOrElse("?")}, attempt ${seen.attempt}")
    effects().done()

  @DeleteHandler
  def onDelete(): Consumer.Effect =
    val subject = messageContext().eventSubject().toScala
    ProbeLog.record("todo", subject, "delete", "deleted", messageContext().metadata().asScala.map(_.getKey).toList.sorted)
    effects().done()

  extension [A](o: java.util.Optional[A]) private def toScala: Option[A] = if o.isPresent then Some(o.get) else None
