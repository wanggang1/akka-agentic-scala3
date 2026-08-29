package com.gwgs.akkaagentic.todos.application

import java.util.Optional

import akka.javasdk.annotations.{Component, Consume, Query, Table}
import akka.javasdk.view.{TableUpdater, View}

import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.todos.domain.TodoSummary

/** The CQRS **read side** over capability 6's per-username `TodoEntity` (capability 11).
  *
  * The write side (`TodoEntity`, a `KeyValueEntity` keyed by username) can only ever answer "what is
  * *this* user's list?" — an entity is addressable only by its own id. This View is the projection
  * that makes the other question answerable: *across all assistants*, who still has open work. It
  * stores one summary row per username, refreshed by the runtime whenever that user's entity state
  * changes, and is eventually consistent by construction.
  *
  * ==Two Scala interop findings live in this file==
  *
  * '''1. The View component itself is Scala — but the `TableUpdater` MUST sit in the companion
  * object.''' This is a new hazard class for this project: not a `Class`- vs method-ref API question,
  * but a **bytecode-shape** requirement. The SDK finds updaters with `Class.getDeclaredClasses()` and
  * instantiates them with `getDeclaredConstructor()` (zero-arg) + `newInstance()`. A Scala *inner*
  * class (`class TodoSummaryView { class Updater }`) compiles to a non-static class carrying a
  * `private final $outer` field and **only** a `Updater(TodoSummaryView)` constructor — unconstructable
  * by the SDK, and a runtime failure. The companion-object form below compiles to a `public static`
  * member class with a synthesized no-arg constructor: byte-for-byte the shape a Java
  * `public static class` produces. Proven by compile spike (feature 013 research R2). '''Do not
  * "simplify" this back into the class body.'''
  *
  * '''2. Querying this View from Scala is impossible, so the endpoint is Java.''' `ViewClient` exposes
  * only `method(Function<T, QueryEffect<R>>)` overloads keyed on a Java `SerializedLambda` method
  * reference, with **no `dynamicCall` escape hatch** — the same wall as `WorkflowClient` (README §4)
  * and the entity clients (README §6, §8). A Scala lambda compiles to a synthetic `$anonfun` and never
  * resolves. So `TodoSummaryEndpoint` is Java (research R1), and by the language-of-consumer rule
  * (README §8) the row record `TodoSummaryEntry` is Java too. The wall claimed the *client*, not the
  * component — the through-line of this whole series.
  */
@Component(id = "todo-summary-view")
class TodoSummaryView extends View:

  /** One user's standing, by username. Keyed lookup, so at most one row can match.
    *
    * Returns `Optional` rather than the row directly: a lookup for a username that has never had a
    * to-do list is a legitimate "no row", not an error.
    */
  @Query("SELECT * FROM todo_summaries WHERE username = :username")
  def getByUsername(username: String): View.QueryEffect[Optional[TodoSummaryEntry]] =
    queryResult()

  /** Every assistant still holding at least one open item.
    *
    * This is the question the write side cannot answer at all: a `KeyValueEntity` is addressable only
    * by its own id, so "across all users, who still has work outstanding?" would mean fanning out over
    * every username. The projection answers it with one indexed query — the reason this capability
    * exists.
    *
    * The `SELECT * AS entries` wrapper form is mandatory for a query that can match many rows
    * (AGENTS.md); a bare `SELECT *` returning a list is rejected. No `ORDER BY` is declared, so
    * callers must not depend on row order.
    *
    * A user whose items are all completed has `openCount == 0` and is absent. Nobody matching is an
    * empty `entries` list — a successful answer, never a not-found.
    */
  @Query("SELECT * AS entries FROM todo_summaries WHERE openCount > 0")
  def withOpenWork(): View.QueryEffect[TodoSummaryEntries] =
    queryResult()

object TodoSummaryView:

  /** Projects each `TodoEntity` state change into one `todo_summaries` row.
    *
    * Declared here in the **companion object**, not inside `class TodoSummaryView` — see the class
    * scaladoc, finding 1. This is the one placement the SDK's reflection accepts.
    *
    * `onUpdate` receives the entity's *whole current state* (a `KeyValueEntity` projects state, not
    * events — hence `onUpdate`, never `onEvent`), so the row is **replaced wholesale** every time and
    * is a pure function of the latest list. Nothing accumulates, so redelivery cannot double-count —
    * on top of which entity-sourced views already get exactly-once delivery with per-entity
    * sequence-number deduplication from the runtime.
    */
  @Table("todo_summaries")
  @Consume.FromKeyValueEntity(classOf[TodoEntity])
  class Updater extends TableUpdater[TodoSummaryEntry]:

    def onUpdate(list: TodoList): TableUpdater.Effect[TodoSummaryEntry] =
      // eventSubject is the source entity id, which for TodoEntity is the username (the row key).
      effects().updateRow(TodoSummary.from(updateContext().eventSubject().get, list))
