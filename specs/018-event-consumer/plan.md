# Implementation Plan: Reacting to activity (Consumer)

**Branch**: `018-event-consumer` | **Date**: 2026-09-19 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/018-event-consumer/spec.md`

## Summary

A Scala **Consumer** watches capability 6's to-do lists and keeps an **activity feed** of what changed —
added, completed, reopened, removed — that a reader can fetch over HTTP, and publishes each change to a
topic. Nothing asks it to; it reacts.

Phase 0 measured all seven questions ([research.md](research.md)). Four shape this plan:

- **The whole family is Scala-clean.** Authoring, receiving, deleting and publishing involve no method
  reference; a published payload goes through the *Scala-aware* mapper. **No Java in production.**
- **A failing message is redelivered without limit, and it blocks every other user.** Backoff doubles
  (0 → 27.6 s and climbing); a change for another user waited the full 30 s. The consumer therefore bounds
  its own attempts and sets a message aside — measured to unblock the stream within 1 ms.
- **The consumer resumes after a restart; it does not replay.** An in-process feed would lose its history
  silently, so the feed states the window it covers.
- **One topic-producing consumer stops the whole service starting locally**, unless eventing support is
  configured. `application.conf` sets it to `logging`.

## Technical Context

**Language/Version**: Scala 3.3.8 (LTS) on Java 21 (Temurin) — **no Java in production**; Java appears only in
tests that must write capability 6's Java entity (a method reference)
**Primary Dependencies**: `akka-javasdk` 3.6.3 only — **no new dependency**
**Storage**: none durable. The feed is in-process, bounded, and labels the window it covers (D8/D9)
**Testing**: JUnit 5 + AssertJ + Awaitility. **No model**, mocked or live. Delivery shape and publishing on
the TestKit's mocked channels; **failure behaviour on the real projection** (the mock drops failing
messages — research Q-F)
**Target Platform**: the existing service; one consumer, one read endpoint, one topic
**Performance Goals**: a change appears in the feed within seconds (measured sub-second on the real path)
**Constraints**: FR-010 (read capability 6, never change it — provable by `git diff`); domain free of Akka
imports; idiomatic Scala, no mutable state (one atomic cell over an immutable value); bounded attempts
that never stall the stream; bounded retention; offline-first
**Scale/Scope**: one consumer, one Scala endpoint, a small pure domain, ~6 test classes

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1 — still passing.*

| Principle | Status | Evidence |
|---|---|---|
| **I. Akka SDK First** (non-negotiable) | **PASS** | An SDK `Consumer` over an SDK entity, publishing through `@Produce.ToTopic`, read through an SDK endpoint. No new dependency. |
| **II. Design Principles** | **PASS** | *Domain independence*: diffing two to-do lists and the feed's rules are pure Scala with no Akka import. *API isolation*: endpoint-owned wire types. *Single responsibility*: the consumer adapts, the domain decides, the store holds, the endpoint reads. |
| **III. Test Coverage** | **PASS** | Pure unit tests for the diff, feed rules and attempt bound; real-path integration tests for failure; mocked-path tests for delivery and publishing; the Phase 0 probes kept as evidence. |
| **IV. Simplicity** | **PASS** | No entity, no workflow, no model. The in-process feed is a stated trade-off (D8), not an omission. |

No deviations requiring justification — the first capability since 13 with **no Java in production at all**.

## Project Structure

### Documentation (this feature)

```text
specs/018-event-consumer/
├── spec.md · plan.md · research.md · data-model.md · quickstart.md
├── contracts/todo-activity.md
├── checklists/requirements.md
└── tasks.md          # /akka.tasks output — NOT created here
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/feed/domain/
├── TodoChange.scala             # Added | Completed | Reopened | Removed | ListDeleted | Resync — pure
├── TodoDiff.scala               # (previous list, current list) => changes — pure; the heart of D2
└── ActivityFeed.scala           # entries + set-asides + last-state per user; retention; idempotence — pure

src/main/scala/com/gwgs/akkaagentic/feed/application/
├── ActivityStore.scala          # ONE AtomicReference over the immutable ActivityFeed (cap-15's pattern)
├── BoundedDelivery.scala        # attempt bound keyed by (consumer, user, state fingerprint) — D3/D4
├── FeedSettings.scala           # feed.max-attempts, retention bounds — refused out of range (ConfigException)
└── TodoActivityConsumer.scala   # @Consume.FromKeyValueEntity(TodoEntity) + @Produce.ToTopic("todo-activity")

src/main/scala/com/gwgs/akkaagentic/feed/api/
└── TodoActivityEndpoint.scala   # GET /todo-activity, /todo-activity/{username}, /todo-activity/set-aside

src/main/scala/com/gwgs/akkaagentic/feed/probe/   # EXISTS (Phase 0) — kept as evidence (FR-013)
```

**Structure Decision**: the capability's own package, so FR-010 is provable by `git diff`. Every production
file is Scala; the tree itself states the verdict.

## Key design decisions

The full table is in [research.md](research.md#consolidated-decisions); the ones that shape the code:

1. **The reaction is a pure diff.** `TodoDiff(previous, current)` turns two whole lists into changes. A key-value
   source delivers state, not events (Q-B), so this is where "what happened" is decided — and it is pure, so it
   is unit-tested with no runtime.
2. **Bounded attempts, then set aside** (D3/D4). The consumer runs each delivery under `BoundedDelivery`,
   keyed by (user, fingerprint of the state) because the SDK gives no attempt number and `ce-id` changes on
   every redelivery. After `feed.max-attempts` it records a set-aside with the reason and returns `done()`.
   Never throwing past the bound is what keeps one bad message from stalling every user.
3. **Idempotence by comparison with the last applied state** (D5). A duplicate is an identical state; its diff
   is empty; nothing is recorded or published.
4. **In-process, and honest about it** (D8). Every feed response carries `since`, the moment this process
   began recording; the README states that a restart empties the feed while the consumer, which resumes rather
   than replays, will not resend what it already delivered.
5. **Publishing** (D6/D7): one idiomatic message per delivery with changes, `ce-subject` = username, topic
   `todo-activity`; `eventing.support = logging` keeps local runs starting.
6. **Failure tested where it is real** (D10): the mocked channel drops failing messages, so failure tests
   write to the real entity — which is why those tests are Java.

## Complexity Tracking

No constitution violations. Two deliberate trade-offs, recorded rather than hidden:

| Trade-off | Why | Rejected alternative |
|---|---|---|
| In-process feed that forgets on restart | The consumer resumes (Q-E), so a durable feed needs an entity — a method-reference client, i.e. Java — for a sandbox feed | A durable feed: recorded as a fork |
| Exactly-once display is not promised | A key-value state carries no version (Q-E); only consecutive duplicates are provably duplicates | Remembering all past states — would swallow a genuine return to an earlier state |

## User checkpoint (before `/akka.tasks`) — resolved by measurement

**Q-G: capability 7's delegation tasks.** A Scala consumer **can** read the SDK's own task entity — but a
live capability 7 run produced **one** task (the coordinator's own) and none for the specialists it
delegated to (research Q-G). The source cannot say which specialists ran, so it cannot deliver fork B3.
**Recommendation: not added.** Recorded as a finding: reachability proven, value absent for request-based
delegates.

## Phase 2 preview (owned by `/akka.tasks`)

Foundational: `TodoChange`, `TodoDiff`, `ActivityFeed` + unit tests; `ActivityStore`, `BoundedDelivery`,
`FeedSettings`. US1: the consumer + feed endpoint (mocked delivery tests). US2: bounded attempts + set-aside
on the real path (Java tests). US3: duplicates. US4: publishing (outgoing mock + `logging` live). US5: README
§18, FINDINGS, ROADMAP, limitations. Polish: trim the probes' 30 s sampling loops (measurements cited),
FR-010 diff, quickstart walk, `mvn clean verify`.
