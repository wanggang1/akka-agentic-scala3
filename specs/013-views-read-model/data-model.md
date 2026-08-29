# Phase 1 Data Model: To-do Read Model (cap-11)

**Feature**: `013-views-read-model` | **Date**: 2026-08-28

Three types are introduced and one existing type is read. Nothing existing is modified.

---

## 1. `TodoSummaryEntry` — the view row *(new, **Java** record)*

`src/main/java/com/gwgs/akkaagentic/todos/application/TodoSummaryEntry.java`

| Field | Type | Meaning |
|---|---|---|
| `username` | `String` | the `TodoEntity` id this row summarizes; the row key |
| `totalCount` | `int` | number of items on the list |
| `openCount` | `int` | items with `completed == false` |
| `completedCount` | `int` | items with `completed == true` |

**Invariant**: `openCount + completedCount == totalCount`. Enforced by construction — every field is
derived together by `TodoSummary.from` (below), never set independently.

**Why Java** (research R3): the row crosses the SDK's internal serializer (so it must be Java-*shaped*)
**and** it is consumed by the Java endpoint (so under README §8's language-of-consumer rule it must be
Java-*authored*, since a Java→Scala dependency is forbidden).

**Not a domain type.** It exists only as a projection of `TodoList`; nothing writes it directly and it
never leaves the endpoint unmapped (FR-010).

---

## 2. `TodoSummaryEntries` — multi-row query result *(new, **Java** record)*

`src/main/java/com/gwgs/akkaagentic/todos/application/TodoSummaryEntries.java`

| Field | Type | Meaning |
|---|---|---|
| `entries` | `List<TodoSummaryEntry>` | all matching rows; **empty, never null**, when nothing matches |

Required by the SDK's multi-row convention (AGENTS.md): a query that can select many rows returns a
record with a single list field, selected as `SELECT * AS entries FROM …`.

---

## 3. `TodoSummary` — the derivation *(new, **Scala**, pure)*

`src/main/scala/com/gwgs/akkaagentic/todos/domain/TodoSummary.scala`

```
TodoSummary.from(username: String, list: TodoList): TodoSummaryEntry
```

The single place the counts are computed. Pure, total, no Akka import, no I/O — so it is unit-testable
with no runtime (Constitution II: domain independence; III: test coverage).

- Reads the **Java** `TodoList` and returns the **Java** `TodoSummaryEntry`: a Scala→Java dependency,
  which is the direction README §8 permits.
- `totalCount = list.todos.size`; `completedCount` = count of `completed`; `openCount = total - completed`
  (computed by subtraction so the invariant cannot drift).
- A `TodoList` with an empty `todos` yields `(0, 0, 0)` — a legitimate row, distinct from *no row*
  (spec edge case: never-touched user vs. emptied list).
- `nextId` is deliberately **not** projected: it is a write-side id-allocation detail with no meaning
  to a reader (Constitution IV: simplicity).

---

## 4. `TodoList` — the source *(existing, **unchanged**)*

`src/main/java/com/gwgs/akkaagentic/a2a/domain/TodoList.java` — `record TodoList(List<Todo> todos, int nextId)`,
the `TodoEntity` state keyed by username. Cap-11 **reads** it and changes nothing (SC-006).

---

## Projection

```
TodoEntity (KeyValueEntity, id = username)
        │  whole-state change  (KVE emits its full value, so the updater gets the complete TodoList)
        ▼
TodoSummaryView.Updater  extends TableUpdater[TodoSummaryEntry]
        │  onUpdate(list) = effects().updateRow(TodoSummary.from(updateContext().eventSubject().get, list))
        ▼
table `todo_summaries`   —  one row per username, replaced wholesale on every update
        │
        ├── SELECT * FROM todo_summaries WHERE username = :username          → one row
        └── SELECT * AS entries FROM todo_summaries WHERE openCount > 0      → TodoSummaryEntries
```

**Row key**: `updateContext().eventSubject()` — the source entity id, i.e. the username.

**Why the row is a summary and not one row per item**: the updater is scoped to a single row and is
handed the whole `TodoList` at once; it cannot fan one entity update out into N item rows. This is the
constraint the spec chose to embrace rather than work around.

**Replacement, not accumulation**: each update recomputes all counts from the incoming state, so
duplicate or redelivered updates are idempotent by construction — independent of the platform's
built-in exactly-once sequence-number deduplication (spec edge case).

**Deletion**: cap-6 has no entity-delete path, so `deleteRow()` is not wired. If a delete is ever
added, `publishDelete(username)` + a `@DeleteHandler` would be the extension point — explicitly not
built now (Constitution IV: YAGNI).
