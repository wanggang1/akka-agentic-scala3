# Data model: session compaction (capability 17)

Nothing here is persisted by us. The conversation lives in the runtime-owned `SessionMemoryEntity`; our own
state is one in-process value. Types are grouped by which side of the two-mapper boundary (README §3) they
sit on, because that decides their shape.

## Owned by the runtime — read, never defined by us

| Type | What we use it for | Notes from Phase 0 |
|---|---|---|
| `SessionMemoryEntity.Event.AiMessageAdded` | the **trigger**. `historySizeInBytes: Long` | The **only** event carrying the running size (S-5) — so the check is once per turn, with no entity read, and can never fire mid-turn |
| `SessionMemoryEntity.Event.HistoryCleared` | evidence that a compaction landed | One of the three events `compactHistory` persists (S-6) |
| `SessionHistory` | the summariser's input; carries `sequenceNumber: Long` | The sequence number is the concurrency guard's input (R-4) |
| `SessionMemoryEntity.CompactionCmd` | the write: `(UserMessage, AiMessage, sequenceNumber)` | A stale sequence number is **accepted silently and does nothing** (R-4) |
| `SessionMessage.{UserMessage, AiMessage}` | the summary pair | `AiMessage` has a 3-arg constructor with **no** token usage, which is the fallback S-4 did not need |

## Pure domain — Scala, immutable, no Akka import

### `CompactionThreshold`

The configured size past which a session is summarised, and the switch that disables compaction.

| Field | Type | Rule |
|---|---|---|
| `maxBytes` | `Long` | **1 KiB ≤ maxBytes ≤ 256 KiB.** The upper bound is the load-bearing one: it must stay well below the SDK's own 510 KiB eviction (S-1), or eviction reaches the oldest turns first and compaction summarises a history whose beginning is already gone |
| `enabled` | `Boolean` | `false` reproduces capability 6's behaviour exactly |

An out-of-range value is the **server's** fault, surfaced as `ConfigException.BadValue` at read time — never
`require`, and never reported to a caller as a `400` (capability 15, limitations §7d).

### `CompactionDecision`

```text
decide(historyBytes: Long, threshold: CompactionThreshold): Decision
  Leave                     — below the threshold, or disabled. The common case; costs nothing.
  Compact(historyBytes)     — at or above it.
```

Pure and total, so the trigger holds no branching logic of its own. A `Leave` must be free: no entity read,
no model call, no store write.

### `SummaryRequest`

Turns a `SessionHistory`'s messages into the single text a summariser reads. Pure formatting, and the place
the tool-call substance is preserved as prose rather than as structured calls (FR-002): a tool request and
its response become readable lines, so the summary can carry what a tool *did* without carrying a tool
call that could later be orphaned.

| Field | Type | Notes |
|---|---|---|
| `text` | `String` | `USER:` / `AI:` / `TOOL_CALL_REQUEST:` / `TOOL_CALL_RESPONSE:` sections, in order |
| `messageCount` | `Int` | Carried so the ledger can record what was replaced |

### `HistoryLine` — the neutral conversation type

The SDK hands us `akka.javasdk.agent.SessionMessage`, and the constitution's Principle II keeps `domain`
free of Akka. So the formatting rule — the part worth unit-testing, because it decides what survives a
compaction — works on `HistoryLine.{User, Ai, ToolResult}` and the application layer maps at the boundary.
*(A departure from this document's first draft and from tasks.md T005, both of which had `SummaryRequest`
taking `SessionMessage` directly. Caught while implementing: it would have dragged the SDK into the domain
to save one small mapper.)*

### `ConversationSummary` — **Java-shaped**, and in `application` not `domain`

*(Also corrected while implementing: its `@Description` hints are an Akka annotation, so it belongs beside
capability 3's `HelpAnswer`, which lives in `application` for the same reason.)*

The summariser agent's result type. Jackson-annotated with plain fields, like capability 3's `HelpAnswer`
and capability 16's change message, because component payloads do **not** go through the Scala-aware mapper
(README §3).

| Field | Type | Rule |
|---|---|---|
| `userMessage` | `String` | must not be blank — an empty summary is a failed compaction, not a small one |
| `aiMessage` | `String` | must not be blank |

### `CompactionLedger` — the observable record

One immutable value holding what happened, with `(Ledger, Result)` transitions and bounded retention (the
capability 15/16 pattern). Never a `var`, never a mutable collection.

| Field | Type | Notes |
|---|---|---|
| `since` | `Instant` | The window this value can speak for — it is in-process and lost on restart, as in capabilities 15 and 16, so every read says so |
| `records` | `Map[String, SessionRecord]` | Keyed by session id |
| `maxSessions` | `Int` | Bounded; least-recently-changed evicted first |

**`SessionRecord`**

| Field | Type | Notes |
|---|---|---|
| `compactions` | `Int` | How many times this session has been summarised |
| `lastBytesBefore` | `Long` | Size that crossed the threshold |
| `lastBytesAfter` | `Long` | Size after — **read back from the entity**, not predicted (R-4) |
| `lastMessagesReplaced` | `Int` | How many turns the summary stands in for |
| `lastAt` | `Instant` | |
| `lastOutcome` | `Outcome` | `Compacted` · `SkippedStale` · `Failed(reason)` |

`Outcome` is why the ledger exists rather than a counter: R-4 measured that a stale write is accepted
silently, so "we asked" and "it happened" are different facts and the record must distinguish them.
`Failed` also covers a summariser that errored or returned a blank summary — FR-005 requires the session be
left exactly as it was, and the record is how that becomes observable rather than invisible.

## State transitions

```text
                    below threshold
   ┌──────────────────────────────────────────────┐
   │                                              ▼
turn completes ──► Compact ──► summarise ──► write ──► re-read ──► Compacted
                      │            │                      │
                      │            └─ error/blank ────────┼──► Failed(reason)   history untouched
                      │                                   │
                      └────────── history unchanged ──────┴──► SkippedStale     newer turn won
```

The `re-read` step is not defensive padding; it is the only way to tell `Compacted` from `SkippedStale`,
because the platform reports neither (R-4).

## What is deliberately absent

- **No entity of ours.** The runtime already owns the history. Adding one would drag the method-reference
  wall into a second place for no gain (capability 5's reasoning).
- **No archive of pre-compaction turns.** Compaction replaces; the summarised turns are not recoverable.
  Session history is a working context, not an audit log — stated in the spec's assumptions.
- **No loop guard.** R-2 measured that compaction's own `AiMessageAdded` reports 22 bytes, so a
  size-keyed trigger cannot re-fire. A test pins that number, because it is the SDK's ordering and not our
  property to rely on silently.
