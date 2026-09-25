# Data Model: Reacting to activity (capability 16)

All of it is **pure domain** (no Akka import) except the store, which holds one immutable value in one
atomic cell. Nothing is durable — see [research.md](research.md) Q-E and D8.

## 1. `TodoSnapshot` (domain)

The feed's **own** copy of one user's list, converted from capability 6's Java `TodoList` in the consumer.
The domain does not import capability 6's types: reading capability 6 is allowed (FR-010), coupling this
capability's rules to its classes is not necessary.

| Field | Type | Notes |
|---|---|---|
| `items` | `Map[Int, TodoItem]` | keyed by capability 6's item id; `TodoItem(description, completed)` |
| `nextId` | `Int` | capability 6's high-water mark — never rewinds |

`fingerprint: String` — a deterministic rendering (`id:description:completed|…#next=n`). It is the identity
of "this delivery" for the attempt bound and for idempotence, because the SDK provides no stable one: no
attempt number, and `ce-id` changes on every redelivery (research Q-D/Q-E).

## 2. `TodoChange` (domain) — what the feed records

| Kind | Wire `kind` | When |
|---|---|---|
| `Added(id, description)` | `added` | an id present now, absent before |
| `Completed(id, description)` | `completed` | `completed` went false → true |
| `Reopened(id, description)` | `reopened` | `completed` went true → false |
| `Removed(id, description)` | `removed` | an id present before, absent now |
| `ListDeleted` | `list-deleted` | the whole entity was deleted (the delete handler) |
| `Baseline(open, completed)` | `baseline` | **first sighting** of a user's list — no previous state to compare |

`Baseline` exists because reporting every pre-existing item as `added` the first time a user is seen would
be false: the consumer may start long after those items were created, and a key-value source does not
replay history. It is also what an evicted user's next change produces (D9).

## 3. `TodoDiff` (domain)

```scala
def between(previous: Option[TodoSnapshot], current: TodoSnapshot): List[TodoChange]
```

`None` → `List(Baseline(...))`. Otherwise the changes above, ordered by item id so the result is
deterministic. **Identical snapshots → `Nil`** — this is the idempotence rule (D5): a duplicate delivery is
an identical state and records nothing.

A key-value source may fold several changes into one delivery (the SDK documents it), so one delivery can
yield several changes, and an item added *and* removed between two deliveries yields nothing — the feed is
an activity record, not an audit log (spec FR-004).

## 4. `ActivityFeed` (domain) — the one immutable value

| Field | Meaning |
|---|---|
| `since: Instant` | when this process began recording — returned on every read (D8) |
| `entries: Vector[ActivityEntry]` | newest **500** retained, oldest dropped (D9) |
| `setAsides: Vector[SetAside]` | newest **100** retained |
| `lastState: Map[String, TodoSnapshot]` | at most **1000** users; the least recently changed is evicted |
| `nextSequence: Long` | monotonic across the feed |

`ActivityEntry(sequence, username, change, recordedAt)`.
`SetAside(username, fingerprint, attempts, reason, recordedAt)` — never counted as activity, never published.

Operations, each returning `(ActivityFeed, result)` and never changing the receiver:

- `record(username, snapshot, at)` → the new entries (possibly none)
- `deleted(username, at)` → a `ListDeleted` entry; forgets the user's last state
- `setAside(username, fingerprint, attempts, reason, at)`

## 5. Attempt bound (application) — `BoundedDelivery`

Capability 15's `BoundedAttempts`, adapted: attempts are counted per **(user, fingerprint)** in a bounded map
inside the store; below `feed.max-attempts` a failure is rethrown so the runtime retries; at the bound the
delivery is set aside and the handler returns `done()`. Measured necessary: without it one failing message
is redelivered without limit **and blocks every other user** (research Q-D).

## 6. Settings (application) — `FeedSettings`

| Key | Default | Range |
|---|---|---|
| `feed.max-attempts` | 3 | 1..10 |
| `feed.max-entries` | 500 | 1..10000 |
| `feed.max-set-asides` | 100 | 1..1000 |
| `feed.max-users` | 1000 | 1..100000 |

Out-of-range values throw `ConfigException.BadValue` — **not** `require`, which the SDK reports as a
caller's `400` when an endpoint fails to construct (capability 15, limitations §7d).
