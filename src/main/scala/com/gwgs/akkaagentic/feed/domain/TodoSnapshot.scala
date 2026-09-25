package com.gwgs.akkaagentic.feed.domain

/** One item as the feed sees it. */
final case class TodoItem(description: String, completed: Boolean)

/** One user's to-do list as the feed sees it, converted from capability 6's state at the boundary.
  *
  * The feed keeps its **own** type on purpose: reading capability 6 is allowed (FR-010), but coupling this
  * capability's rules to another capability's classes is not necessary, and the domain must stay free of
  * anything the SDK serializes.
  *
  * **No Akka import** (Constitution II).
  */
final case class TodoSnapshot(items: Map[Int, TodoItem], nextId: Int):

  /** A deterministic rendering of this exact state.
    *
    * It is the identity of "this delivery" — for the attempt bound and for recognising a duplicate —
    * because the SDK provides no other: a consumer gets no attempt number, `ce-id` changes on every
    * redelivery, and a key-value state carries no version (specs/018 research Q-D/Q-E).
    */
  def fingerprint: String =
    items.toList.sortBy(_._1).map((id, item) => s"$id:${item.description}:${item.completed}").mkString("|") + s"#next=$nextId"

  def open: Int = items.count(!_._2.completed)
  def completedCount: Int = items.count(_._2.completed)

object TodoSnapshot:
  val empty: TodoSnapshot = TodoSnapshot(Map.empty, 1)
