package com.gwgs.akkaagentic.feed.probe

import scala.jdk.CollectionConverters.*

import akka.javasdk.annotations.{Component, Consume, DeleteHandler}
import akka.javasdk.consumer.Consumer
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.feed.application.BoundedDelivery.Outcome
import com.gwgs.akkaagentic.feed.application.{BoundedDelivery, FeedSettings, TodoActivityConsumer}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/** The FR-006/007 instrument: a consumer whose deliveries can be made to fail on demand.
  *
  * It exists because the production consumer's work — comparing two lists and recording the difference —
  * has no realistic way to fail, so there would otherwise be nothing to test the bound against. It runs its
  * work through **`BoundedDelivery`, the same helper the production consumer uses**, so what the failure
  * test proves is the shipped code path and not a copy of it (capability 15's shape).
  *
  * It also keeps `ProbeLog` as an **independent witness** of how many times the runtime actually delivered:
  * the store cannot testify to that, because once a delivery is set aside its attempt count is cleared.
  *
  * Kept registered beyond Phase 0 as the FR-013 evidence, and harmless in production: it fails only for
  * subjects a test has poisoned.
  */
@Component(id = "todo-probe-consumer")
@Consume.FromKeyValueEntity(classOf[TodoEntity])
class TodoProbeConsumer(config: Config) extends Consumer:
  private val logger = LoggerFactory.getLogger(classOf[TodoProbeConsumer])
  private val attemptLimit = FeedSettings.maxAttempts(config)

  def onUpdate(list: TodoList): Consumer.Effect =
    val subject = messageContext().eventSubject().orElse("unknown")
    val snapshot = TodoActivityConsumer.snapshotOf(list)
    val detail = s"${list.todos().asScala.map(t => s"${t.id}:${t.description}:${t.completed}").mkString("[", ",", "]")} next=${list.nextId()}"
    val keys = messageContext().metadata().asScala.map(e => if e.getKey == "ce-id" then s"ce-id=${e.getValue}" else e.getKey).toList.sorted
    // The witness is recorded BEFORE the bound runs: it counts deliveries the runtime made, which is a
    // different question from how many attempts the bound has counted.
    val seen = ProbeLog.record("todo", Some(subject), "update", detail, keys)
    logger.info("todo probe: delivery for [{}] (witness #{}) : {}", subject, seen.attempt, detail)

    BoundedDelivery.run("todo-probe-consumer", subject, snapshot.fingerprint, attemptLimit) {
      val switches = ProbeLog.switches
      if switches.poisoned(subject) && seen.attempt < switches.giveUpAfter then
        throw RuntimeException(s"probe: poisoned subject $subject, delivery ${seen.attempt}")
      if ProbeLog.consumeFailOnce(subject) then
        throw RuntimeException(s"probe: first delivery for $subject fails, the next will succeed")
    } match
      case Outcome.Succeeded(_) => effects().done()
      case Outcome.WillRetry(attempt, cause) =>
        logger.info("todo probe: [{}] failed attempt {} of {}; asking for a redelivery", subject, attempt, attemptLimit)
        throw cause
      case Outcome.GaveUp(attempt, _) =>
        logger.warn("todo probe: [{}] set aside after {} attempts; the stream continues", subject, attempt)
        effects().done()

  @DeleteHandler
  def onDelete(): Consumer.Effect =
    ProbeLog.record("todo", Some(messageContext().eventSubject().orElse("unknown")), "delete", "deleted", Nil)
    effects().done()
