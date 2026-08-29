package com.gwgs.akkaagentic.todos.application

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** The result of a `todo_summaries` query that can match many rows.
  *
  * The SDK requires this wrapper shape: a multi-row query must return a record with a single list
  * field, selected as `SELECT * AS entries FROM …`. A bare `SELECT *` returning a list is not valid
  * (AGENTS.md).
  *
  * `entries` is empty, never null, when nothing matches — "no assistant has open work" is a successful
  * answer, not an error or a not-found.
  *
  * Java-shaped for the same reason as [[TodoSummaryEntry]] (it crosses the internal serializer), and
  * likewise authored in **Scala**. The list is a `java.util.List`, not a Scala `List`, because the
  * annotation-free internal mapper must be able to construct it — the same rule cap-3's `HelpAnswer`
  * follows.
  */
final case class TodoSummaryEntries @JsonCreator() (
    @JsonProperty("entries") entries: java.util.List[TodoSummaryEntry]
)
