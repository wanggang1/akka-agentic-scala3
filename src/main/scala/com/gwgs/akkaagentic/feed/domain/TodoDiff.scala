package com.gwgs.akkaagentic.feed.domain

/** Works out **what changed** between two states of one user's list.
  *
  * This exists because the source delivers **state, not events**: a key-value source hands the consumer
  * the whole list every time (specs/018 research Q-B). So "what happened" is derived here — and being
  * pure, it is unit-tested with no runtime at all.
  *
  * Two consequences the feed is honest about (spec FR-004):
  *   - the platform may **fold** several changes into one delivery, so one comparison can yield several
  *     changes, and an item added *and* removed between two deliveries leaves no trace;
  *   - an identical state yields **nothing**, which is what makes a duplicate delivery a no-op (D5).
  */
object TodoDiff:

  def between(previous: Option[TodoSnapshot], current: TodoSnapshot): List[TodoChange] =
    previous match
      case None           => List(TodoChange.Baseline(current.open, current.completedCount))
      case Some(`current`) => Nil
      case Some(before)   => changes(before, current)

  private def changes(before: TodoSnapshot, current: TodoSnapshot): List[TodoChange] =
    val added = current.items.collect {
      case (id, item) if !before.items.contains(id) => TodoChange.Added(id, item.description)
    }
    val removed = before.items.collect {
      case (id, item) if !current.items.contains(id) => TodoChange.Removed(id, item.description)
    }
    val toggled = current.items.collect {
      case (id, item) if before.items.get(id).exists(_.completed != item.completed) =>
        if item.completed then TodoChange.Completed(id, item.description)
        else TodoChange.Reopened(id, item.description)
    }
    // Ordered by item id so the result is deterministic; within one item only one kind can apply.
    (added ++ removed ++ toggled).toList.sortBy(_.itemIdOpt.getOrElse(Int.MaxValue))
