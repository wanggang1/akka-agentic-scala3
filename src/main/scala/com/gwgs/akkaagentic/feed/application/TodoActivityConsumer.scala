package com.gwgs.akkaagentic.feed.application

import scala.jdk.CollectionConverters.*

import akka.javasdk.annotations.{Component, Consume, DeleteHandler}
import akka.javasdk.consumer.Consumer
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.feed.application.BoundedDelivery.Outcome
import com.gwgs.akkaagentic.feed.domain.{TodoItem, TodoSnapshot}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/** Reacts to capability 6's to-do lists: notices what changed, and records it. Nobody asks it to.
  *
  * **Scala all the way through** (specs/018 research Q-A): a Consumer is declared by an annotation carrying
  * a class and returns effects from `effects()` — there is no method reference anywhere in the family, so
  * unlike capability 11's View over the *same* entity, nothing here is forced into Java.
  *
  * **It reads capability 6 and never writes it** (FR-010). The only writer is capability 6's own assistant,
  * through its Java tool object; this consumer is a second, independent projection over the same source.
  *
  * **What arrives is state, not an event.** A key-value source hands over the whole list every time
  * (research Q-B), so the change is worked out by comparing against the last state seen for that user —
  * which is also what makes a duplicate delivery a no-op, since an identical state differs in nothing.
  *
  * **Every delivery runs under a bound it enforces itself** (`BoundedDelivery`). Measured: a handler that
  * throws is redelivered without limit *and* holds up every other user's changes until it stops throwing
  * (research Q-D). So a delivery that cannot be processed is set aside after `feed.max-attempts`, and this
  * returns `done()` so the stream moves on for everyone else.
  */
@Component(id = "todo-activity-consumer")
@Consume.FromKeyValueEntity(classOf[TodoEntity])
class TodoActivityConsumer(config: Config) extends Consumer:
  import TodoActivityConsumer.*

  private val logger = LoggerFactory.getLogger(classOf[TodoActivityConsumer])
  private val attemptLimit = FeedSettings.maxAttempts(config)
  ActivityStore.configure(config)

  def onUpdate(list: TodoList): Consumer.Effect =
    val username = subject
    val snapshot = snapshotOf(list)
    BoundedDelivery.run(Name, username, snapshot.fingerprint, attemptLimit) {
      val recorded = ActivityStore.record(username, snapshot)
      if recorded.nonEmpty then
        logger.info("activity for [{}]: {}", username, recorded.map(_.change.kind).mkString(", "))
    } match
      case Outcome.Succeeded => effects().done()
      case Outcome.WillRetry(attempt, cause) =>
        logger.warn(s"activity delivery for [$username] failed, attempt $attempt of $attemptLimit; retrying", cause)
        throw cause
      case Outcome.GaveUp(attempt, cause) =>
        logger.error(s"activity delivery for [$username] set aside after $attempt attempts; the stream continues", cause)
        effects().done()

  /** The whole list was deleted at the source. Recorded as a removal rather than left to look like silence. */
  @DeleteHandler
  def onDelete(): Consumer.Effect =
    val username = subject
    ActivityStore.deleted(username)
    logger.info("activity for [{}]: list-deleted", username)
    effects().done()

  /** The entity id, which for capability 6's `TodoEntity` is the username. */
  private def subject: String = messageContext().eventSubject().orElse("unknown")

object TodoActivityConsumer:
  val Name = "todo-activity-consumer"

  /** Convert capability 6's Java state into the feed's own type, at the boundary — so the domain never
    * depends on another capability's classes. */
  def snapshotOf(list: TodoList): TodoSnapshot =
    TodoSnapshot(
      list.todos().asScala.map(t => t.id() -> TodoItem(t.description(), t.completed())).toMap,
      list.nextId())
