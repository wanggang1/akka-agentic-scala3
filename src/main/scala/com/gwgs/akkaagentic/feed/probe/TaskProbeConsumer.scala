package com.gwgs.akkaagentic.feed.probe

import akka.javasdk.agent.task.{TaskEntity, TaskEvent}
import akka.javasdk.annotations.{Component, Consume}
import akka.javasdk.consumer.Consumer
import org.slf4j.LoggerFactory

/** Q-G probe: can a Scala consumer subscribe to the **runtime-owned** `akka.javasdk.agent.task.TaskEntity`
  * (component id `akka-task`)? It is a public `final` class extending `EventSourcedEntity[TaskState,
  * TaskEvent]`, so it satisfies the annotation's type bound; whether the runtime lets an application
  * consume it is the measurement. `TaskAssigned.assignee` is the field capability 7's ground truth
  * (ROADMAP fork B3) would need.
  */
@Component(id = "task-probe-consumer")
@Consume.FromEventSourcedEntity(classOf[TaskEntity])
class TaskProbeConsumer extends Consumer:
  private val logger = LoggerFactory.getLogger(classOf[TaskProbeConsumer])

  def onEvent(event: TaskEvent): Consumer.Effect =
    val detail = event match
      case e: TaskEvent.TaskCreated    => s"TaskCreated(${e.name})"
      case e: TaskEvent.TaskAssigned   => s"TaskAssigned(${e.name}, assignee=${e.assignee})"
      case e: TaskEvent.TaskStarted    => s"TaskStarted(${e.name})"
      case e: TaskEvent.TaskCompleted  => s"TaskCompleted(${e.name})"
      case e: TaskEvent.TaskFailed     => s"TaskFailed(${e.name}, ${e.reason})"
      case other                       => other.getClass.getSimpleName
    ProbeLog.record("task", Option(messageContext().eventSubject().orElse(null)), "event", detail, Nil)
    logger.info("task probe: [{}] {}", messageContext().eventSubject().orElse("?"), detail)
    effects().done()
