# Research: Scheduled reminders (capability 15)

**Feature**: `017-timed-action` · **Date**: 2026-09-18 · **SDK**: 3.6.3

Every answer below was **measured**, not read. The probes are kept as evidence (FR-013):
`TimedActionProbeIntegrationTest` (Scala, drives both surfaces), `ReminderProbeEndpoint` (Scala
scheduler attempt + Scala cancel) and `JavaReminderProbeEndpoint` (the Java control).

---

## Q-A — Can a Scala caller **schedule**? **NO. This is the headline's first half.**

`TimerScheduler.createSingleTimer(name, delay, deferred)` is keyed on values, so the wall — if any — is
in producing the third argument. The jar says only one thing produces it:

| Path to a `DeferredCall` | Available? |
|---|---|
| `TimedActionClient.method(japi.Function \| Function2)` → `ComponentDeferredMethodRef.deferred()` | yes — **method reference** |
| any id-keyed / `dynamicCall` form on `TimedActionClient` | **does not exist** |
| `DynamicMethodRef` (what `dynamicCall` returns elsewhere) | **has no `deferred()`** |

The Scala lambda form **compiles** — as in capabilities 13 and 14 — and then fails at run time:

```text
IllegalArgumentException: Use dedicated builder for calling Object component method
  ReminderProbeEndpoint::$anonfun$1. This builder is meant for Action component calls.
```

The timer was never created and nothing fired within 2 s.

**This diagnostic is better than its predecessors, and worth recording as such.** Capability 13 and 14
both got *"class &lt;the caller's own class&gt; is not a subclass of …"*, which never mentions lambdas.
Here the message names the synthetic **`$anonfun$1`** outright and says the builder was handed an
"Object component method" — i.e. the resolver saw a method on the *endpoint*, not on the action. Same
root cause, far more legible. A reader who hits this has a fighting chance of understanding it.

**Control:** the identical schedule from **Java** returns `SCHEDULED`, fires, and carries its note
intact — and the action it schedules is **Scala**. So the failure is a statement about the Scala
*caller*, not about the action, the descriptor, or our usage.

---

## Q-B — Can a Scala caller **cancel**? **YES — and that is the finding.**

`TimerScheduler.delete(String)` is keyed on a plain string. Measured end to end: a timer **scheduled
from Java** was **cancelled from Scala**, and after waiting past its delay it had fired **0 times**.

So **one component family sits on both sides of the method-reference wall**, split by operation:

| Operation | API | From Scala |
|---|---|---|
| schedule | `TimedActionClient.method(...)` → `deferred()` | **impossible** |
| cancel | `TimerScheduler.delete(String)` | **works** |
| perform the work | `TimedAction` + `effects()` | **works** (the action is Scala) |

Capability 14 found one *client* with two kinds of method. This is different and sharper: **two
different APIs govern two halves of one feature**, so the wall runs *through* the family rather than
around it. You can cancel what you cannot schedule.

---

## Q-C — Is a pending timer durable? **NO, in local dev mode — measured twice.**

| Step | Time |
|---|---|
| scheduled a 60 s timer (on-disk store enabled) | 09:52:17 |
| killed the service, 10 s later, `db.mv.db` present | 09:52:27 |
| restarted on the same store, healthy | 09:52:35 |
| watched 100 s, past the 09:53:17 due time | **never fired** |

A first attempt was discarded rather than reported: it killed the service in the *same second* as
scheduling, so "not durable" could have meant "never persisted". The 10 s gap above removes that.

**Scope, stated rather than glossed:** this is `dev-mode.persistence.enabled=true` with the local H2
store — the same flag under which capabilities 3 and 5 *did* observe their tasks surviving. So the
result is specifically that **timers are not restored the way tasks are**, locally. A deployed service
has a real datastore and may differ; that is unverified here and must not be claimed either way.

**Design consequence (D5 below).** A durable *state* store plus a non-durable *timer* would let a
reminder read "pending" for ever after a restart — a lie to the caller. Matching the state's lifetime
to the timer's is the honest shape.

---

## Q-D — What is the retry contract? **The 3-argument form retries indefinitely. Measured.**

An action that always throws, watched for 30 s:

| Overload | Attempts at 5s / 10s / 15s / 20s / 25s / 30s |
|---|---|
| `createSingleTimer(name, delay, **maxRetries = 2**, deferred)` | 2, then **stops** |
| `createSingleTimer(name, delay, deferred)` — the 3-arg default | **2, 3, 3, 3, 4, 4 — still climbing**, with widening gaps |

So the SDK's warning ("handle errors to avoid infinite rescheduling") is not theoretical: the default
keeps retrying with backoff, and 6 s of observation would have missed it — the first measurement
stopped there and read "2 and 2", which looked bounded and was not.

**This makes FR-008 a shipping rule, not a preference: the four-argument overload with an explicit
`maxRetries` is mandatory in this capability.** The three-argument form must not appear in production
code, and a test should pin that.

---

## Q-E — Can firing be observed offline, cheaply? **Yes. Delays can be sub-second.**

`TimedActionTestkit.call(...)` invokes the action **directly**, which proves the action's behaviour but
never that a timer fired — so the timer itself has to be observed by waiting. Measured overhead:

| Requested | Fired after |
|---|---|
| 100 ms | 214 ms |
| 300 ms | 326 ms |
| 1000 ms | 1070 ms |

Roughly 100 ms of overhead, and reliable at 300 ms. So SC-001/SC-003 are provable offline with
**sub-second delays**, and the suite cost is seconds, not minutes. Cancellation still needs a wait past
the delay to prove a negative, which is the one unavoidable cost.

---

## Consolidated decisions

| # | Decision | Rationale | Alternatives rejected |
|---|---|---|---|
| D1 | The **scheduling** caller is **Java**, one class | Q-A: measured runtime failure for the Scala form; Java control works | Scala-only (no reachable path); whole capability in Java (concedes more than measured) |
| D2 | **Cancelling stays Scala** | Q-B: measured working against a Java-scheduled timer | Putting cancel in Java too — would hide the finding and grow the quarantine |
| D3 | The **`TimedAction` is Scala** | Authoring is `effects()`-based with no method refs; the Java control schedules a Scala action | — |
| D4 | **Always the 4-arg overload** with explicit `maxRetries` | Q-D: the 3-arg form was still retrying at 30 s | Relying on the default (FR-008 violation) |
| D5 | Reminder **state lives in process**, lifetime matched to the timer's | Q-C: timers do not survive restart locally; durable state + volatile timer = a reminder stuck "pending" for ever | A `KeyValueEntity` — durable, but its client is method-ref-only, so it would grow the Java quarantine *and* create the inconsistency above |
| D6 | Test delays **300 ms–1.5 s** | Q-E: ~100 ms overhead, reliable at 300 ms | Multi-second delays (slow suite); testkit-only (cannot observe firing) |
| D7 | The HTTP surface is **split by language**: Java owns `POST` (schedule), Scala owns read and cancel | Forced by D1/D2 — and it makes the finding visible in the file layout rather than only in prose | One Java endpoint owning everything (hides that cancel is Scala-clean) |

## What remains unverified

1. **Whether timers are durable in a deployed service** (non-dev datastore). Not testable here; the
   capability documents the local result and claims nothing about production.
2. **What `maxRetries = 2` counts** — 2 total invocations were observed, but whether that is "1 attempt
   + 1 retry" or "2 retries" is not distinguishable from the outside. Bounded is what FR-008 needs;
   the exact accounting is noted as unknown rather than guessed.
