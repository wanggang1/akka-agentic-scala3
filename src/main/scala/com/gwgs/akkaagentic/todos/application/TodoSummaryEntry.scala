package com.gwgs.akkaagentic.todos.application

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** One row of the `todo_summaries` view: a single assistant's to-do standing, keyed by username
  * (capability 11, the CQRS read side over capability 6's `TodoEntity`).
  *
  * **Java-shaped, but authored in Scala.** View rows are (de)serialized by the SDK's *internal*
  * `impl.serialization.Serializer` — the Jackson mapper that `Bootstrap`'s `DefaultScalaModule` hook
  * does **not** reach (the two-mapper boundary, README "Scala interop notes" §3). So the row must be
  * Java-*shaped*: explicit `@JsonCreator`/`@JsonProperty` and plain fields the annotation-free mapper
  * can construct. It need not be Java-*authored*, which is the same shape cap-3's `HelpAnswer` and
  * cap-1's `GreetingAgent.Result` already use.
  *
  * That distinction is the point of feature 013's R3. This row was originally a Java record, on the
  * belief that a Java class could not reference a Scala one in this build — true at the time, but a
  * **build defect**, not a language boundary (javac ran before scalac; fixed in `pom.xml`). With both
  * directions compiling, the Java quarantine shrinks to exactly one class: `TodoSummaryEndpoint`, the
  * only place a `ViewClient` method reference is held. Which is precisely the finding this capability
  * claims — *the wall travels no further than the class that holds the method reference.*
  *
  * **Invariant**: `openCount + completedCount == totalCount`, true by construction because every field
  * is derived together by [[com.gwgs.akkaagentic.todos.domain.TodoSummary.from]] and nothing else ever
  * builds a row. An empty to-do list projects to `(0, 0, 0)` — a legitimate row, distinct from *no row
  * at all*.
  *
  * Not a domain type, and never returned outward: the endpoint maps it to its own response record.
  *
  * @param username the `TodoEntity` id this row summarizes; also the row key
  * @param totalCount number of items on the list
  * @param openCount items still to do (`completed == false`)
  * @param completedCount items done (`completed == true`)
  */
final case class TodoSummaryEntry @JsonCreator() (
    @JsonProperty("username") username: String,
    @JsonProperty("totalCount") totalCount: Int,
    @JsonProperty("openCount") openCount: Int,
    @JsonProperty("completedCount") completedCount: Int
)
