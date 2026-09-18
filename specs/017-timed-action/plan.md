# Implementation Plan: Scheduled reminders (Timed Action)

**Branch**: `017-timed-action` | **Date**: 2026-09-18 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/017-timed-action/spec.md`

## Summary

Add a reminder surface: `POST /reminders` schedules a note to fire after a delay, `GET /reminders/{id}`
reads its state, `DELETE /reminders/{id}` cancels it before it fires. Behind it, a Scala `TimedAction`
does the work when the delay elapses.

Phase 0 measured all five open questions ([research.md](research.md)). Three shape this plan:

- **Scheduling cannot be done from Scala; cancelling can.** The scheduling API needs a Java method
  reference and the escape hatch has no `deferred()`; `delete(String)` is plain-string keyed and works
  from Scala against a Java-scheduled timer. **One family, wall running through the middle of it.**
- **The three-argument `createSingleTimer` retries indefinitely** (still climbing at 30 s). The
  four-argument overload with an explicit `maxRetries` is mandatory, not preferred.
- **Timers do not survive a restart locally**, so durable reminder state would strand callers on
  "pending" for ever. State lifetime is matched to the timer's instead.

## Technical Context

**Language/Version**: Scala 3.3.8 (LTS) on Java 21 (Temurin); one Java 21 class, by measurement
**Primary Dependencies**: `akka-javasdk` 3.6.3 only — **no new dependency**
**Storage**: none durable, deliberately (research D5/Q-C). Reminder state is in-process and shares the
timer's lifetime; a restart loses both, consistently.
**Testing**: JUnit 5 + AssertJ + Awaitility; **no model at all**, mocked or live — the project's second
entirely model-free capability after capability 11. Timer firing is observed by waiting 300 ms–1.5 s.
**Target Platform**: the existing service; new HTTP paths only.
**Project Type**: single Akka service (this repository).
**Performance Goals**: a reminder fires within ~100 ms of its due time (measured overhead).
**Constraints**: offline-first; no existing capability's sources or tests modified (FR-010); `domain`
free of Akka imports; retries **bounded** (FR-008); any Java confined to the smallest unit that works
and pinned by a test (FR-013).
**Scale/Scope**: one timed action, two endpoints, one small domain type, ~5 test classes.

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1 — still passing.*

| Principle | Status | Evidence |
|---|---|---|
| **I. Akka SDK First** (non-negotiable) | **PASS** | The feature is an SDK `TimedAction` scheduled through the SDK's `TimerScheduler` and exposed by SDK endpoints. **No new dependency.** |
| **II. Design Principles** | **PASS** | *Domain independence*: note/delay validation is pure Scala, no Akka import. *API isolation*: endpoints own their request/response types. *Single responsibility*: the action performs, the endpoints schedule/read/cancel, the store records. *Descriptive naming*: `ReminderAction`, `ReminderStore`, `Reminder`. |
| **III. Test Coverage** | **PASS** | Unit tests for the pure validation and the state machine; integration tests for fire, cancel, the four states, bounded retry, and validation-first. The Phase 0 probes stay as permanent evidence (FR-013). |
| **IV. Simplicity** | **PASS** | No entity, no workflow, no model. The in-process store is a deliberate *reduction* justified by Q-C, not a shortcut: durable state here would create a lie rather than a benefit. |

**Deviation requiring justification**: one **Java** class in an otherwise-Scala capability. See
Complexity Tracking — forced by a measured SDK property, confined to the smallest unit that works.

## Project Structure

### Documentation (this feature)

```text
specs/017-timed-action/
├── spec.md · plan.md · research.md · data-model.md · quickstart.md
├── contracts/reminder-endpoints.md
├── checklists/requirements.md
└── tasks.md          # /akka.tasks output — NOT created here
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/reminders/domain/
└── ReminderRequest.scala          # pure validation (note non-blank, delay positive+bounded); NO Akka import
                                   # + ReminderState (pending/fired/cancelled/failed) as a pure enum

src/main/scala/com/gwgs/akkaagentic/reminders/application/
├── ReminderAction.scala           # EXISTS (Phase 0): the Scala TimedAction; fire + failAlways
└── ReminderStore.scala            # in-process state, lifetime matched to the timer (D5).
                                   # Replaces the probe's ReminderLog.

src/main/java/com/gwgs/akkaagentic/reminders/api/
└── ReminderSchedulingEndpoint.java   # the ONLY Java class: POST /reminders. Holds the method
                                      # reference, uses the 4-ARG overload, validates via the Scala domain

src/main/scala/com/gwgs/akkaagentic/reminders/api/
└── ReminderEndpoint.scala            # GET /reminders/{id} and DELETE /reminders/{id} — read and cancel,
                                      # both Scala-clean (delete is String-keyed)

src/main/resources/META-INF/akka-javasdk-components_…conf
                                      # `timed-action` key (DONE) + the two endpoints

src/test/scala/com/gwgs/akkaagentic/reminders/…      # domain unit tests, endpoint integration tests
src/test/scala/com/gwgs/akkaagentic/reminders/probe/ # EXISTS: the Phase 0 probe, kept as evidence
src/main/…/reminders/probe/                          # EXISTS: probe endpoints — to be REPLACED by the
                                                     # real surface, with the Scala attempt kept (FR-013)
```

**Structure Decision**: the capability's own package, so FR-010 is provable by `git diff`. The
Java/Scala split follows the measurement exactly: **the class that holds the method reference is Java;
the class that only cancels and reads is Scala.** That split is visible in the directory layout, which
is the point — this capability's finding is that the wall runs through one family, and the file tree
says so.

## Key design decisions

1. **Java schedules, Scala cancels and reads** (research D1/D2). Not a stylistic split — measured.
2. **`maxRetries` is always explicit** (D4). The 3-argument overload is banned in production code and a
   test pins its absence, the way capability 12 pinned "the agent names no rule".
3. **No durable state** (D5). Q-C showed timers do not survive a restart, so durable reminder state
   would report "pending" for a reminder that can never fire. Matching lifetimes is the honest option,
   and it keeps the Java quarantine at **one** class — a `KeyValueEntity` would have needed a
   method-ref caller of its own (capability 6's shape) and grown it to two.
4. **No model** (spec Assumptions). Second fully model-free capability; the whole suite is deterministic.
5. **Cancel reports three outcomes** — cancelled, already-fired, unknown (FR-006) — because "cancelled"
   for something that already happened is a lie the caller would act on.
6. **Delays are bounded at both ends**: a minimum (below which the ~100 ms overhead dominates) and a
   maximum (this is a sandbox; long horizons raise clock/redeploy questions that are out of scope).

## Complexity Tracking

| Violation | Why needed | Simpler alternative rejected because |
|---|---|---|
| One **Java** class (`ReminderSchedulingEndpoint`) | Scheduling needs a `DeferredCall`, obtainable only from a Java method reference (Q-A, measured with a working Java control) | *Scala-only*: no reachable path — the capability would have no way to schedule. *All-Java*: concedes cancel and the action too, which measurement shows are Scala-clean |
| An **in-process** store rather than durable state | Q-C: timers are not restored after a restart locally | *Durable entity*: would leave reminders permanently "pending" after a restart **and** add a second Java class for its method-ref caller |

## Phase 2 preview (owned by `/akka.tasks`)

Foundational: the domain type + state enum and their unit tests; promote the probe's `ReminderLog` into
`ReminderStore`. US1: the Java scheduling endpoint, the Scala read endpoint, fire-and-observe tests.
US2: cancel, with all three outcomes. US3: bounded-retry test plus the "no 3-arg overload" pin. US4:
README §17, FINDINGS, ROADMAP, `docs/sdk-3.6.0-limitations.md` for the non-durable timer and the
retrying default. Finally: replace the probe endpoints with the real surface (keeping the Scala attempt
as evidence), `mvn clean verify`, and the FR-010 diff check.

---

## Addendum — 2026-09-18, during implementation

The design above held; these are the places the shipped code differs from it, each for a recorded reason.
Kept as an addendum rather than an edit, so the plan still shows what was decided *before* the code.

| Plan said | Shipped | Why |
|---|---|---|
| `ReminderStore` over a `ConcurrentHashMap`, transitions via `compute` | **one `AtomicReference` over an immutable `Reminders` value**; every rule a pure `Reminders => (Reminders, Result)` in the domain, committed by compare-and-set | PR review: the `compute` version smuggled its outcome out through a captured `var`, correct only because `compute` runs once under a lock — not checkable locally, not idiomatic. A 32-thread cancel race now pins "exactly one winner". |
| `record(id, note, delay)` | `record(id, note)` | The delay was never used, and its doc claimed otherwise. |
| `ReminderAction` with `fire` + `failAlways` | `fire` only; the instrument is `probe/FailingReminderAction` | The production action keeps one handler; the instrument stays visibly an instrument. |
| Timer `maxRetries` bounds a failing reminder (D4) | D4 kept, **plus** the action bounds itself: it counts its attempts and, on the last, records `failed` and returns `done()`; bound read from `reminders.max-retries` (1..10) | Found during US3: an exhausted timer stops **silently** and the SDK gives an action **no attempt number** — bounded alone would leave the reminder `pending` for ever. See `docs/sdk-3.6.0-limitations.md` §7c. |
| Test delays 300 ms–1.5 s (D6) | **1–1.5 s** for HTTP-level tests; 300 ms only through the testkit's own scheduler | `POST /reminders` rejects delays under 1 s (`ReminderRequest.MinDelay`). D6 was the probe's floor. |
| Java probe retired in polish | Retired inside Phase 5, between the retry test and the no-3-arg pin | So the pin covers the whole capability with **no exemption list**. |
| `ScalaScheduleAttempt` is the kept Q-A evidence | Kept, but documented as the **compile-time** half only; the executed evidence is the lambda in `ReminderProbeEndpoint` | Nothing runs `ScalaScheduleAttempt`; the quoted diagnostic names `ReminderProbeEndpoint::$anonfun$1`. Kept by review decision, with that stated in its scaladoc. |
