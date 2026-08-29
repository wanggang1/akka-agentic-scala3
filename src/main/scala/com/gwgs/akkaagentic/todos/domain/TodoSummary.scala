package com.gwgs.akkaagentic.todos.domain

import scala.jdk.CollectionConverters.*

import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.todos.application.TodoSummaryEntry

/** The projection of capability 6's `TodoList` write-side state onto capability 11's read-model row.
  *
  * This is the *only* place the counts are computed. It is pure and total — no Akka import, no I/O,
  * no runtime — so the whole derivation is unit-testable with no TestKit and no model (Constitution
  * II: the domain layer does not depend on the application layer or the SDK).
  *
  * It reads the **Java** `TodoList` and returns the **Java** `TodoSummaryEntry`: a Scala&rarr;Java
  * dependency, which is the direction README "Scala interop notes" §8 permits (never Java&rarr;Scala).
  * See `TodoSummaryEntry`'s javadoc for why the row itself must be Java.
  */
object TodoSummary:

  /** The read-model row for `username` given that assistant's current to-do list.
    *
    * `openCount` is computed **by subtraction** rather than by a second filter, so the invariant
    * `openCount + completedCount == totalCount` cannot drift if the two predicates ever disagree.
    *
    * `nextId` is deliberately **not** projected: it is a write-side id-allocation high-water mark, not
    * something a reader of the summary has any use for.
    *
    * An empty list yields `(0, 0, 0)` — a real row meaning "this assistant has no to-dos", which is
    * distinct from there being no row for the username at all.
    */
  def from(username: String, list: TodoList): TodoSummaryEntry =
    val total = list.todos.size
    val completed = list.todos.asScala.count(_.completed)
    TodoSummaryEntry(username, total, total - completed, completed)
