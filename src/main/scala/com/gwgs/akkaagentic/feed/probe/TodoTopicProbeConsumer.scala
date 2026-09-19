package com.gwgs.akkaagentic.feed.probe

import scala.jdk.CollectionConverters.*

import akka.javasdk.Metadata
import akka.javasdk.annotations.{Component, Consume, DeleteHandler, Produce}
import akka.javasdk.consumer.Consumer
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList

/** Q-C probe: does `effects().produce(...)` under `@Produce.ToTopic` work from Scala, what does the
  * outgoing message look like, and which serializer handles it?
  *
  * The produced type is deliberately an **idiomatic** Scala case class with an `Option` field. If topic
  * payloads go through the SDK's internal mapper (README §3), this is exactly the shape that fails there
  * with "Cannot construct instance of scala.Option" — so it is a measurement, not a design choice.
  * `ce-subject` is set because the docs require it for per-entity ordering on a broker.
  */
@Component(id = "todo-topic-probe-consumer")
@Consume.FromKeyValueEntity(classOf[TodoEntity])
@Produce.ToTopic("todo-activity-probe")
class TodoTopicProbeConsumer extends Consumer:

  def onUpdate(list: TodoList): Consumer.Effect =
    val subject = messageContext().eventSubject().orElse("?")
    ProbeLog.record("topic", Some(subject), "update", s"open=${list.todos().asScala.count(!_.completed())}", Nil)
    effects().produce(
      TodoTopicProbeConsumer.ProbeActivity(subject, list.todos().size(), list.todos().asScala.headOption.map(_.description())),
      Metadata.EMPTY.add("ce-subject", subject))

  @DeleteHandler
  def onDelete(): Consumer.Effect = effects().ignore()

object TodoTopicProbeConsumer:
  final case class ProbeActivity(username: String, total: Int, firstDescription: Option[String])
