# Phase 0 research: session compaction (capability 17)

**Method**: measured against the SDK 3.6.3 artifacts before any design, as every capability since 13 has
done. Findings below marked **static** were read out of the shipped bytecode and `reference.conf`; those
marked **runtime** still need a probe that runs.

> **Headline: the spec's premise was wrong, and the corrected one is better.** Capability 6's session
> history is **not** unbounded, and it is **not** at risk of the orphaned-tool-pair bug. Both claims came
> from reasoning by analogy with `readLast(N)`, and both are false. What compaction actually fixes is
> different, and worth stating precisely — see S-1 and S-2.

---

## S-1 — History is ALREADY bounded, at 510 KiB, by default *(static; premise-correcting)*

`akka-javasdk-3.6.3.jar!/reference.conf`:

```hocon
akka.javasdk.agent.memory {
  enabled = true
  # The maximum size of the memory window for the session history.
  # This is calculated as the sum of all messages content length in bytes.
  # Once the limit is reached, older messages will be automatically removed in a FIFO approach.
  # The default value is 510 KiB and this is actually the maximum value allowed. ...
  limited-window.max-size = 510 KiB
}
```

So "unbounded token growth" — the phrase capability 6's README and this spec both used — is **not what
happens**. Growth stops at 510 KiB, which the SDK also documents as the **maximum permitted** value,
because session messages are routed around the Akka cluster.

Note what `MemoryProvider.limitedWindow()` does *not* have: any size setting at all. Its builder exposes
only `readLast(int)`, `readOnly`, `writeOnly`, `filtered` (bytecode-verified). The byte bound is
**configuration**, applied to the entity through `setLimitedWindow(LimitedWindow(maxSizeInBytes))`, not
something the agent asks for. That is why capability 6 never knew it had one.

**Consequence for the design, and it is a hard ordering constraint**: our threshold MUST sit well below
510 KiB. Above it, the SDK's eviction gets there first and has already discarded the oldest turns by the
time we would summarise — we would be compacting a history whose beginning is gone.

## S-2 — That eviction is TURN-ALIGNED and safe. `readLast(N)` is not. *(static; reverses the hypothesis)*

The probe set out to show that the 510 KiB FIFO had the same defect as `readLast(N)` at a higher
threshold. **It does not.** `SessionMemoryEntity$State.enforceMaxCapacity` runs two loops:

1. while `currentSizeInBytes > maxSizeInBytes` → `List.removeFirst()`, logging *"Removed oldest message
   for sessionId [{}]"*;
2. **then** while the head is neither a `UserMessage` nor a `MultimodalUserMessage` → `removeFirst()`,
   logging *"Removed orphan message for sessionId [{}]"*.

The second loop is the whole difference. It guarantees the retained history **begins at a user turn**, and
since eviction only ever removes from the head, what remains is always a suffix starting at a user
message. A tool-call/response pair lives *inside* a turn, so a cut aligned to turn boundaries can never
split one.

**The precise, reusable statement**: the SDK has two history-shrinking mechanisms and they differ in
exactly one property — whether the cut is aligned to a turn boundary.

| | Mechanism | Aligned to a turn? | Orphans a tool pair? |
|---|---|---|---|
| **write** side (entity state, byte bound) | `enforceMaxCapacity` — FIFO **plus an orphan sweep** | **yes**, head is forced to a `UserMessage` | **no** |
| **read** side (`MemoryProvider…readLast(N)`) | `MemoryHistoryUtils.trimToLastN` — `subList(size-N, size)` | **no**, arbitrary index | **yes** — capability 6's live failure |

So capability 6's failure was specific to `readLast(N)`, and dropping it was the correct and *sufficient*
fix for that bug. This capability is not repairing a latent orphan risk, because there isn't one.

## S-3 — What compaction is actually for, restated *(follows from S-1 and S-2)*

Two real problems remain, and they are the honest motivation:

1. **A bounded history is not a small one.** 510 KiB of text is on the order of 100k+ tokens, re-sent on
   every turn of a long session. That is a cost and latency problem even though it terminates.
2. **Eviction discards meaning.** The FIFO drops the *oldest turns entirely* — safely, but with nothing
   left behind. A conversation quietly loses its beginning. `State.truncated()` is a boolean recording
   that this happened, so the loss is detectable but not recoverable.

Compaction is the only mechanism available that shrinks history **while keeping what it meant**: it
replaces the turns with prose instead of deleting them. That is a better justification than the one the
spec was written on, and it is the one to publish.

## S-4 — Q-C: `dynamicCall` has no detailed reply, and the wall claims a THIRD method *(static)*

```
akka.javasdk.client.DynamicMethodRef<A1, R>   // what dynamicCall returns
  withMetadata, withRetry(RetrySettings), withRetry(int), invoke, invokeAsync   // and nothing else
```

`withDetailedReply()` exists on exactly four types, and every one is reached from the **method-reference**
path: `AgentMethodRef`, `AgentMethodRef1`, `AgentInvokeOnlyMethodRef`, `AgentInvokeOnlyMethodRef1`.

So the amendment capability 14 began continues. The agent client's escape hatch covers **plain
request/response and nothing else**: capability 1 found `dynamicCall` rescues `invoke`; capability 14
found `tokenStream` is method-ref only; capability 17 finds `withDetailedReply` is too.

**Is token usage load-bearing?** No — and there are two ways out, one better than the other:

- `SessionMessage$AiMessage` has a **three-argument constructor** `(Instant, String text, String
  componentId)` with no token usage, and `CompactionCmd` only needs *an* `AiMessage`. So a Scala-clean
  `dynamicCall` is possible at the price of the summary reporting no token cost — which makes the
  session's own `getTokenUsage()` under-report for ever after.
- **Better**: the Java class that Q-B already forces to exist can hold the agent call too, keeping token
  usage at **zero extra Java**. Preferred, and it makes Q-B and Q-C one decision instead of two.

## S-5 — Q-E: the running size is on `AiMessageAdded` only *(static)*

`SessionMemoryEntity$Event$AiMessageAdded` carries **`historySizeInBytes(): long`**. No other event does —
`UserMessageAdded`, `ToolResponseMessageAdded` and both multimodal variants expose only their own
`sizeInBytes(): int`.

This is better than it sounds. The threshold can be tested **once per turn, on the event that ends the
turn, with no entity read at all** — so the common case (below threshold, do nothing) costs nothing. And
because only the AI message carries it, **the trigger cannot fire mid-turn**, i.e. never between a tool
call and its response. A safety property for free rather than one to engineer.

## S-6 — `compactHistory` emits `AiMessageAdded`, so the trigger can see its own write *(static; hazard)*

`compactHistory(CompactionCmd)` persists **three** events in one `persistAll`:
`HistoryCleared`, `UserMessageAdded`, `AiMessageAdded`.

That third event is the same type the trigger listens to. If the trigger's only test is
`historySizeInBytes > threshold`, compaction's own write could re-trigger compaction — a loop that spends
model calls for ever. Whether it actually does depends on what `historySizeInBytes` reads as *after*
`HistoryCleared` has reset the state, which is a **runtime** question (R-2 below), not one to settle by
staring at bytecode. The design must be robust either way.

`CompactionCmd(UserMessage, AiMessage, long sequenceNumber)` confirms the concurrency guard is a
sequence number, and `State` carries `compactionSeqNr()` and `truncated()` alongside `currentSizeInBytes()`.
`SessionMemoryEntity` also exposes `fetchHistory` returning `SessionHistoryResult` beside
`getHistory` returning `SessionHistory` — the difference is unmeasured and may matter for reading the
sequence number.

## S-7 — The entity id and descriptor key *(static)*

`SessionMemoryEntity` is `public final`, extends `EventSourcedEntity<State, Event>`, and exposes
`COMPONENT_ID`. It is **runtime-owned**: like capability 4, it must **not** be added to the hand-maintained
descriptor. A consumer over it goes under `consumer`, the key capability 16 established.

`Event` is a plain Java interface with record subtypes and a `Event$Message` sub-interface grouping the
four message-added events — so a Scala `match` uses type patterns and needs a default case, with no
exhaustivity guarantee from the compiler.

---

## The runtime half of the probe — measured 2026-09-26

Probes: `src/main/scala/com/gwgs/akkaagentic/compaction/probe/SessionMemoryProbeConsumer.scala` (Scala
consumer over the runtime-owned entity), `src/main/java/…/SessionMemoryProbeGateway.java` (the two entity
method references), `src/test/scala/…/CompactionProbeIntegrationTest.scala` and
`EvictionAlignmentProbeIntegrationTest.scala`. All green; sessions driven through capability 4's
`ChatAgent`, the least-interop agent in the project, so what is measured is the memory mechanism and not an
agent.

### R-1 — A Scala consumer over `SessionMemoryEntity` works. Capability 16's finding extends. ✅

```text
R-1 >>> events seen for [probe-r1]: UserMessageAdded(size=14) | AiMessageAdded(size=22, HISTORY=36)
                                  | UserMessageAdded(size=16) | AiMessageAdded(size=17, HISTORY=69)
```

Zero unmatched events. A plain Scala `Consumer` with
`@Consume.FromEventSourcedEntity(classOf[SessionMemoryEntity])` receives the runtime-owned entity's events
and matches its record subtypes by type pattern. So capability 13's clause — the wall is about *which
client*, not about who owns the component — extends from `TaskEntity` (capability 16) to
`SessionMemoryEntity`. `Event` is a plain Java interface, so the compiler offers no exhaustivity check; a
default case is required and the probe asserts nothing reaches it.

### R-2 — The self-trigger loop hazard is REAL in shape and BENIGN in fact. ✅ *(measured, not assumed)*

`compactHistory` does persist an `AiMessageAdded`, the same event the trigger listens to. But the size it
reports is computed **after** `HistoryCleared` has reset the state:

```text
R-2 >>> before: messages=8 seqNr=9  historySizes=4015,8030,12045,16060
R-2 >>> after:  messages=2 seqNr=12 texts=UserMessage,AiMessage  historySizes=…,16060,22
R-2 >>> LOOP HAZARD: history size reported on compaction's OWN AiMessageAdded = 22 (was 16060)
```

**22 bytes.** So a trigger keyed on `historySizeInBytes > threshold` does **not** re-fire on its own write,
for any sane threshold. The seqNr moving 9 → 12 confirms S-6's three events. The compacted history is
exactly `UserMessage, AiMessage`. No loop guard is needed — but a test should pin this, because it is a
property of the SDK's ordering rather than of our code.

### R-3 — One Java class can hold every method reference. ✅

`SessionMemoryProbeGateway` holds `.method(SessionMemoryEntity::getHistory)` and
`.method(SessionMemoryEntity::compactHistory)` and compiles and runs against the **runtime-owned** entity.
And the agent call can live there too: capability 14's `StreamingChatEndpoint` already holds
`tokenStream(StreamingChatAgent::stream)` — a Java method reference to a **Scala** agent's handler, in
production — so `.method(CompactionAgent::summarize).withDetailedReply()` against a Scala agent needs no
separate proof. **Decision**: one Java class, holding all three method references, keeping the summary's
token usage at zero extra Java (S-4).

### R-4 — The concurrency guard is SILENT. ⚠️ *(the finding that changes the design)*

A stale sequence number is **accepted without error and does nothing**:

```text
R-4 >>> stale sequenceNumber [3] -> ACCEPTED (no error)
R-4 >>> history after the stale write: messages=4     # unchanged — the compaction was discarded
```

So FR-008 is satisfied *by the platform* — newer messages are never lost — but the caller gets **no signal
whatsoever** that compaction was skipped. There is no exception and no result to inspect. A naive
implementation would record "compacted" in its own state while the history was untouched, and report a
bound it never applied.

**Consequence for the design**: compaction must be **verified, not assumed**. The gateway re-reads the
history after writing and compares, and only a confirmed replacement is recorded. This is the same class of
hazard as capability 16's set-aside accounting, where the store could not testify to what the runtime
actually did.

### R-6 — The SDK's own eviction is turn-aligned, observed. ✅ *(S-2 confirmed by measurement)*

510 KiB is impractical to reach in a test, so the bound was overridden to 8 KiB; the mechanism is the same
and only the threshold moves.

```text
R-6 >>> wrote 24 messages under an 8 KiB bound; 8 retained
R-6 >>> EVICTION OCCURRED = true
R-6 >>> retained heads: q9 z,a9 z,q10 ,a10 ,q11 ,a11
R-6 >>> first retained message is a UserMessage
```

The retained window begins at `q9` — a `UserMessage` — and alternates cleanly from there. The cut lands
exactly on a turn boundary, so a tool-call pair cannot be split. **S-2 is now measured, not inferred**, and
with it the corrected premise the whole capability rests on.

*(A first attempt at this used 4000-character padding under the default 510 KiB bound and evicted nothing —
`eviction occurred = false`, 24 of 24 retained. Reported because it is why the override exists: a probe that
does not reach its own trigger proves nothing, and it would have been easy to read that green run as
confirmation.)*

### R-5 — NOT measured. Stated rather than assumed.

Whether capability 14's **streamed** turn tolerates its history being replaced mid-assembly is the one risk
the service-wide decision (FR-014) creates, and it is **unverified**. It needs a compaction fired at a
session while a stream is open — two concurrent things, neither easy to time deterministically. It is
carried into implementation as a task with its own test, not silently assumed safe. What is known: the
trigger fires only on `AiMessageAdded` (S-5), which for a streamed turn is written when the stream
*completes*, so the window for a race is narrower than it first appears — but "narrower" is not "absent".

---

## Found while implementing US1 — three more, all measured

### I-1 — A Java caller cannot construct a Scala 3 `enum` case. It pushed the design the right way. ⚠️

The first `SessionMemoryGateway` did the message mapping and the outcome decision in Java. javac refused:

```text
error: enum classes may not be instantiated
```

`new HistoryLine.User(...)` and `new Outcome.Failed(...)` are both impossible from Java, because a Scala 3
`enum` compiles to something Java sees as an enum class. This is a **new** Java↔Scala wrinkle: capabilities
14 and 15 only *read* Scala values from Java or passed them through, never constructed a sum type.

The workarounds (Scala-side factory methods, or sealed traits instead of `enum`) were both available and
both rejected, because the compiler was pointing at a real design flaw: **the Java class had logic in it.**
It exists only to hold method references the SDK forces into Java, so the mapping and the decision moved to
`Compactor` in Scala, and the gateway became three thin operations that decide nothing. The quarantine is
now one class *and* one responsibility, which is stronger than the file-count pin capabilities 14 and 15
ship.

### I-2 — The event's history size is a HINT, not the authority. ⚠️ *(a real cost bug, caught by running it)*

`AiMessageAdded.historySizeInBytes` is the size **at the moment that event was written**. A burst of turns
crossing the threshold therefore produces a burst of events all reporting a large history, and acting on
each one costs a model call. Measured on the first US1 run, five turns over a 4 KiB threshold:

```text
compaction for [us1-compacts]: SkippedStale (4218 -> 2934 bytes, 0 messages replaced)
compaction for [us1-compacts]: Compacted    (5624 ->  122 bytes, 6 messages replaced)
compaction for [us1-compacts]: SkippedStale (7030 ->  122 bytes, 0 messages replaced)
```

Three summariser calls to perform one compaction, with the platform silently discarding two writes — R-4's
guard doing exactly its job, but only after the tokens were spent. The fix is to re-check the threshold
against the history **actually read**, and return before any model call when it is no longer needed: *the
event tells us to look, the history tells us whether*. After it, the same scenario shows one attempt.

This is the kind of defect a probe cannot find, because it only appears once the trigger, the summariser
and real turns run together.

### I-3 — "Did the write land?" cannot be answered by message count. ⚠️

The first discriminator was `after.messages.size < before.messages.size`. It reported a **successful**
compaction as `SkippedStale`, because a turn that arrives while the summariser is working is appended
afterwards, so the count can be unchanged or higher even though the replacement landed.

The reliable discriminator is the **head** of the history: `compactHistory` clears and re-adds, so if our
summary landed it is first, and later turns append after it. `Compactor` now checks that the first message
carries the compaction component id and our summary's text. Measured: a session driven with six
overlapping turns keeps our summary at the head and is correctly recorded as compacted, while the
deterministic three-turn case is exactly `UserMessage, AiMessage`.

The general shape, and it is the same one R-4 has: **under concurrency, infer nothing from a count.** Ask
for the thing that is invariant.

---

## Decisions this research settles

| # | Decision | Because |
|---|---|---|
| D1 | Trigger is a **Scala** `Consumer` on `SessionMemoryEntity`, keyed on `AiMessageAdded` only | R-1 works; S-5 puts the running size on that event alone, so the check costs no entity read and cannot fire mid-turn |
| D2 | **One Java class** holds `getHistory`, `compactHistory` and the detailed agent call | R-3; keeps token usage (S-4) at zero extra Java, and pins the quarantine at one class as in capabilities 11, 14, 15 |
| D3 | Compaction is **verified by re-reading**, never assumed | R-4: a stale sequence number is accepted silently and does nothing |
| D4 | No loop guard, but a **test pinning** the 22-byte result | R-2: the hazard resolves in the SDK's ordering, which is not our property to rely on silently |
| D5 | Threshold in **bytes**, configurable, disableable, and **range-enforced below 510 KiB** | S-1: above it the SDK's eviction reaches the oldest turns first and compaction would do nothing useful |
| D6 | The summariser is a **Scala** `Agent` whose result is **Java-shaped** | It crosses the internal serializer (README §3), like capability 3's `HelpAnswer` |
| D7 | Observability is an in-process store — **one `AtomicReference`, pure transitions** | The cap-15 review rule; and FR-011 needs it readable without the log |
| D8 | Capability 6 is **untouched**; capabilities 4 and 14 gain the bound without being edited | FR-014/FR-015; the trigger is service-wide and lives entirely in this capability |
| D9 | The Java class holds **no logic at all** — three thin operations, no decisions | I-1: javac cannot construct a Scala 3 `enum` case, which exposed that the first draft had logic in the quarantine |
| D10 | The threshold is re-checked against the history actually read, before any model call | I-2: the event's size is stale in a burst, and acting on it alone spent three summariser calls to do one compaction |
| D11 | A landed write is identified by our summary being at the **head**, never by a message count | I-3: a concurrent turn appends while the summariser works, so a count reported a success as a skip |
