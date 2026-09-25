package com.gwgs.akkaagentic.feed.application

import scala.jdk.CollectionConverters.*

import akka.javasdk.Metadata
import akka.javasdk.annotations.{Component, Consume, DeleteHandler, Produce}
import akka.javasdk.consumer.Consumer
import com.fasterxml.jackson.annotation.JsonInclude
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.feed.application.BoundedDelivery.Outcome
import com.gwgs.akkaagentic.feed.domain.{ActivityEntry, TodoItem, TodoSnapshot}
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
  * **Each delivery that produced changes is also published** to the `todo-activity` topic, one message per
  * delivery, with `ce-subject` set to the username so a broker keeps one user's messages in order. Nothing
  * is published for a duplicate (there is nothing to say) or for a set-aside (it is a failure, not activity).
  * The payload is an **idiomatic** Scala type: measured (research Q-C), a produced message goes through the
  * Scala-aware mapper, unlike a component payload, so `Option` fields serialize as plain values.
  *
  * **Every delivery runs under a bound it enforces itself** (`BoundedDelivery`). Measured: a handler that
  * throws is redelivered without limit *and* holds up every other user's changes until it stops throwing
  * (research Q-D). So a delivery that cannot be processed is set aside after `feed.max-attempts`, and this
  * returns `done()` so the stream moves on for everyone else.
  */
@Component(id = "todo-activity-consumer")
@Consume.FromKeyValueEntity(classOf[TodoEntity])
@Produce.ToTopic(TodoActivityConsumer.Topic)
class TodoActivityConsumer(config: Config) extends Consumer:
  import TodoActivityConsumer.*

  private val logger = LoggerFactory.getLogger(classOf[TodoActivityConsumer])
  private val attemptLimit = FeedSettings.maxAttempts(config)
  ActivityStore.configure(config)

  /** The handler. **Selected by its PARAMETER TYPE, not by its name and not by overriding anything** —
    * `Consumer` declares no abstract handler, and the SDK's `ReflectiveConsumerRouter` holds a
    * `Map[payload type, MethodInvoker]`. `TodoList` is what `@Consume.FromKeyValueEntity(classOf[TodoEntity])`
    * delivers, which is the whole reason this method is called; the name `onUpdate` is free choice (the SDK's
    * own examples use `onChange` and `onEvent`). A source with several payload types may declare one handler
    * per type instead.
    *
    * Two mistakes, measured here (2026-09-25) because they fail very differently:
    *   - **Two handlers taking the same type**: compiles, and the runtime **refuses to start** —
    *     `Duplicated update methods [onUpdate, onUpdateTwin] for state subscription are not allowed` /
    *     `Ambiguous handlers for …TodoList`. Eager and loud.
    *   - **A handler taking the WRONG type** (e.g. `String`): compiles, and the service **starts normally**
    *     — the consumer is registered and simply never called. Nothing reports it. The integration tests are
    *     what catch this (every delivery assertion times out), which is why the feed's behaviour is asserted
    *     over HTTP rather than by unit-testing this class.
    */
  def onUpdate(list: TodoList): Consumer.Effect =
    val username = subject
    val snapshot = snapshotOf(list)
    BoundedDelivery.run(Name, username, snapshot.fingerprint, attemptLimit) {
      val recorded = ActivityStore.record(username, snapshot)
      if recorded.nonEmpty then
        logger.info("activity for [{}]: {}", username, recorded.map(_.change.kind).mkString(", "))
      recorded
    } match
      case Outcome.Succeeded(recorded) if recorded.nonEmpty =>
        effects().produce(TodoActivityMessage.of(username, recorded), Metadata.EMPTY.add("ce-subject", username))
      case Outcome.Succeeded(_) => effects().ignore() // a duplicate changed nothing; nothing to publish
      case Outcome.WillRetry(attempt, cause) =>
        logger.warn(s"activity delivery for [$username] failed, attempt $attempt of $attemptLimit; retrying", cause)
        throw cause
      case Outcome.GaveUp(attempt, cause) =>
        logger.error(s"activity delivery for [$username] set aside after $attempt attempts; the stream continues", cause)
        effects().ignore() // a set-aside is a failure, not activity — it is never published

  /** The whole list was deleted at the source. Recorded as a removal rather than left to look like silence.
    *
    * `@DeleteHandler` is needed precisely because type-matching cannot work here: a deletion carries **no
    * payload**, so there is no parameter for the router to match on and an annotation marks the method
    * instead. */
  @DeleteHandler
  def onDelete(): Consumer.Effect =
    val username = subject
    val entry = ActivityStore.deleted(username)
    logger.info("activity for [{}]: list-deleted", username)
    effects().produce(TodoActivityMessage.of(username, List(entry)), Metadata.EMPTY.add("ce-subject", username))

  /** The entity id, which for capability 6's `TodoEntity` is the username. */
  private def subject: String = messageContext().eventSubject().orElse("unknown")

object TodoActivityConsumer:
  val Name = "todo-activity-consumer"

  /** The topic each recorded change is published to. Locally there is no broker, so
    * `akka.javasdk.dev-mode.eventing.support = "logging"` writes the messages to the log instead — without
    * that setting ONE producing consumer stops the whole service starting (`AK-00406`, research Q-C). */
  final val Topic = "todo-activity"

  /** What is published: idiomatic Scala, because a produced payload goes through the Scala-aware mapper
    * (research Q-C) — unlike a component payload, which must stay Java-shaped (README §3).
    *
    * `NON_ABSENT` because the mapper unwraps `Some` but writes `None` as **null**: without it a `baseline`
    * message carried `"itemId":null,"description":null`, which a subscriber would have to interpret.
    * Measured, not assumed — the first run of the publish test printed exactly that. */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class ChangeMessage(kind: String, itemId: Option[Int], description: Option[String])
  final case class TodoActivityMessage(username: String, changes: List[ChangeMessage], recordedAt: String)

  object TodoActivityMessage:
    def of(username: String, entries: List[ActivityEntry]): TodoActivityMessage =
      TodoActivityMessage(
        username,
        entries.map(e => ChangeMessage(e.change.kind, e.change.itemIdOpt, e.change.descriptionOpt)),
        entries.headOption.map(_.recordedAt.toString).getOrElse(""))

  /** Convert capability 6's Java state into the feed's own type, at the boundary — so the domain never
    * depends on another capability's classes. */
  def snapshotOf(list: TodoList): TodoSnapshot =
    TodoSnapshot(
      list.todos().asScala.map(t => t.id() -> TodoItem(t.description(), t.completed())).toMap,
      list.nextId())
