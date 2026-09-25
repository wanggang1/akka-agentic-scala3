package com.gwgs.akkaagentic.feed.domain

/** What the feed records about a change. */
enum TodoChange:
  case Added(itemId: Int, description: String)
  case Completed(itemId: Int, description: String)
  case Reopened(itemId: Int, description: String)
  case Removed(itemId: Int, description: String)

  /** The whole list was deleted at the source (the delete handler). */
  case ListDeleted

  /** The **first** state seen for a user — there was nothing to compare against.
    *
    * It exists so the feed never claims history it did not observe. A key-value source does not replay,
    * so a consumer that starts after items already exist would otherwise report every one of them as
    * freshly `Added`, which would be false. The same entry is recorded for a user whose last state was
    * evicted by retention.
    */
  case Baseline(open: Int, completed: Int)

  /** The wire label, defined beside the cases so the API cannot invent another name for one. */
  def kind: String = this match
    case _: Added     => "added"
    case _: Completed => "completed"
    case _: Reopened  => "reopened"
    case _: Removed   => "removed"
    case ListDeleted  => "list-deleted"
    case _: Baseline  => "baseline"

  /** Named `…Opt` because `itemId`/`description` are already the case parameters. */
  def itemIdOpt: Option[Int] = this match
    case Added(id, _) => Some(id)
    case Completed(id, _) => Some(id)
    case Reopened(id, _) => Some(id)
    case Removed(id, _) => Some(id)
    case _ => None

  def descriptionOpt: Option[String] = this match
    case Added(_, d) => Some(d)
    case Completed(_, d) => Some(d)
    case Reopened(_, d) => Some(d)
    case Removed(_, d) => Some(d)
    case _ => None
