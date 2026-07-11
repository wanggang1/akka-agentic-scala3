package com.gwgs.akkaagentic.assistant.application

import akka.javasdk.agent.task.Task

/** The task type(s) the [[HelpDeskAgent]] accepts.
  *
  * A task definition is a plain constant (no Akka component, so it is **not** listed in the component
  * descriptor). `resultConformsTo` binds the typed result — the SDK uses [[HelpAnswer]] both to build
  * the built-in `complete_task` tool's JSON schema and to deserialize the model's completion. Per-
  * request context is added at call time with `.instructions(question)`.
  */
object HelpDeskTasks:

  val ANSWER: Task[HelpAnswer] =
    Task
      .name("Answer")
      .description("Answer a user's help question, optionally consulting the knowledge base.")
      .resultConformsTo(classOf[HelpAnswer])
