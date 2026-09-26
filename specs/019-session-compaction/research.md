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

## Still to measure — the runtime half of the probe

- **R-1 (Q-A)**: does a Scala `Consumer` over `SessionMemoryEntity` actually receive these events, and can
  a Scala handler match `AiMessageAdded` / `UserMessageAdded` / `HistoryCleared` across the internal
  serializer? Capability 16 proved the analogous thing for `TaskEntity`, but not for this hierarchy.
- **R-2 (S-6's hazard)**: what is `historySizeInBytes` on the `AiMessageAdded` that **compaction itself**
  emits? Decides whether a naive trigger loops.
- **R-3 (Q-B)**: can one Java class hold `getHistory`, `compactHistory` *and* the detailed agent call, with
  the trigger and everything else Scala? And does a **Java** method reference to a **Scala** agent's
  handler resolve (capability 14 did this for `tokenStream`, so it is expected, not assumed)?
- **R-4 (FR-008)**: does the `sequenceNumber` guard actually reject a stale write, and what does the caller
  see when it does — an error, or a silent no-op?
- **R-5 (Q-D consequence)**: does capability 14's **streamed** turn tolerate its history being replaced
  mid-assembly? The one surface where our write races something the SDK is doing for us.
- **R-6**: with `limited-window.max-size` overridden small, confirm S-2 empirically — that a tool-using
  session evicted by size leaves **no** orphan, and that `truncated()` flips. Proves the corrected premise
  by observation and not only by bytecode.
