package com.gwgs.akkaagentic.activities.application

import akka.javasdk.agent.task.Task

/** The task type the [[ActivityCoordinator]] accepts.
  *
  * A plain constant (not a component — not in the descriptor). `resultConformsTo` binds the typed
  * result [[ActivitySuggestion]]. Per-request context is added at call time with
  * `.instructions(question.instruction)`. Mirrors cap-3's `HelpDeskTasks`.
  */
object ActivityTasks:

  val SUGGEST: Task[ActivitySuggestion] =
    Task
      .name("Suggest")
      .description("Suggest activities for a location, consulting weather and activity specialists.")
      .resultConformsTo(classOf[ActivitySuggestion])
