# Data Model: Scheduled reminders (capability 15)

Three types, one of them an enum. There is no entity, no event, and nothing durable — see
[research.md](research.md) Q-C/D5 for why that is the honest shape here rather than a shortcut.

---

## 1. `ReminderRequest` (domain, Scala, **no Akka import**)

What a caller asked for, once it has been proven well-formed. Parse, don't validate: the type's
existence is the proof, so no downstream code re-checks.

| Field | Type | Rule |
|---|---|---|
| `note` | `String` | non-blank, trimmed, ≤ 500 characters |
| `delay` | `scala.concurrent.duration.FiniteDuration` | ≥ `MinDelay` and ≤ `MaxDelay` |

```scala
def validate(note: Option[String], delaySeconds: Option[Int]): Either[String, ReminderRequest]
```

`Option` in, `Either` out — the shape capabilities 8, 13 and 14 all use, and (capability 14's Q-E)
readable from Java at the cost of two casts, which matters because **the caller here is Java**.

| Bound | Value | Why |
|---|---|---|
| `MinDelay` | 1 second | Q-E measured ~100 ms of scheduling overhead; below a second the requested delay stops being the thing that governs when it fires |
| `MaxDelay` | 24 hours | A sandbox bound. Longer horizons raise clock-skew and redeploy questions the capability does not explore — and Q-C says a pending timer does not survive a restart anyway, so a 30-day reminder would be a promise the runtime cannot keep |

Failure messages (each pinned by a unit test, so the HTTP contract cannot drift from the rule):

- `note must not be blank`
- `note must be at most 500 characters`
- `delaySeconds must be between 1 and 86400`

## 2. `ReminderState` (domain, Scala, pure enum)

FR-003's four states. The transitions are what make cancel-after-fired reportable (FR-006).

```text
            ┌──────────► Fired       (the action ran and returned done)
  Pending ──┤
            ├──────────► Cancelled   (delete() before the delay elapsed)
            └──────────► Failed      (the action threw on every permitted attempt)
```

| From | To | Trigger |
|---|---|---|
| `Pending` | `Fired` | the action completed |
| `Pending` | `Cancelled` | `DELETE` arrived first |
| `Pending` | `Failed` | the action threw and `maxRetries` was exhausted |
| any terminal | — | **no transition** — a terminal reminder never moves again |

A `DELETE` on a terminal reminder is therefore **not** an error and **not** a cancellation: it is
reported as already-terminal, with the state it is actually in. Answering "cancelled" to a request
that cancelled nothing is the failure mode FR-006 exists to prevent.

## 3. `ReminderStore` (application, Scala)

In-process, `ConcurrentHashMap`-backed, keyed by reminder id (a UUID string). It is the **promotion of
the Phase 0 probe's `ReminderLog` into the capability's real surface** — same mechanism, now with the
state machine and no test-only helpers.

| Operation | Meaning |
|---|---|
| `record(id, note, delay)` | a reminder has been scheduled → `Pending` |
| `markFired(id)` / `markFailed(id)` | called by the action |
| `cancel(id): CancelOutcome` | `Cancelled` \| `AlreadyTerminal(state)` \| `Unknown` |
| `get(id): Option[Reminder]` | for `GET` |

**Lifetime: the process.** Deliberate, and matched to the timer's own (Q-C). Written down in three
places so nobody reads it as an oversight: here, in `plan.md` D5, and in the `GET` contract.

## Wire types

None cross the SDK's **internal** mapper: the `TimedAction` handler takes a **bare `String`** (the
reminder id) and the store is in-process, so there is no component payload to shape. The two-mapper
boundary (README §3) applies only to the HTTP bodies, which go through `JsonSupport`'s Scala-aware
mapper and stay idiomatic — including the `Option` fields in the responses.

This is the same "least-interop" position capability 4 holds: **no Java-shaped type anywhere in the
capability**, which is worth noting given that the capability nonetheless contains a Java class. The
Java is there for the *method reference*, not for serialization — two different walls, and only one
of them bites here.

---

## Addendum — 2026-09-18, the store as shipped

§3 above describes a `ConcurrentHashMap`-backed store with `record(id, note, delay)`. What shipped, after
PR review asked for idiomatic Scala without mutable state:

- **The rules moved into the domain**, as one immutable value, `Reminders(byId: Map[String, Reminder])`.
  Every operation returns the next value *and* what happened — `fire`, `fail`, `attempt`:
  `(Reminders, Option[Reminder])`; `cancel`: `(Reminders, CancelOutcome)` — and never changes the value
  it was called on. A no-op returns the identical value.
- **`CancelOutcome` moved to the domain** with the rules that produce it: `Cancelled(reminder)`,
  `AlreadyTerminal(reminder)`, `Unknown`.
- **`ReminderStore` holds exactly one `AtomicReference[Reminders]`** and commits each rule with a
  compare-and-set, retrying against the winner on a lost race. Because a rule may be re-run, it must be
  pure — timestamps are taken once, before the retry loop.
- **`record(id, note)`** — the unused `delay` parameter is gone.
- **`attempts`** counts invocations of the work: `fired`, `failed` and `attempted` each add one. It is
  what makes the retry bound demonstrable by count (SC-006).

Terminal still wins: whichever transition commits first stands, and the loser is told what did.
