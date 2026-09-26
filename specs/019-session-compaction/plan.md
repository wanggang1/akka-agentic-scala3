# Implementation Plan: Session compaction

**Branch**: `019-session-compaction` | **Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/019-session-compaction/spec.md`

## Summary

When a session's stored history passes a configured size, replace it with a short prose summary that
keeps what the conversation established. A **Scala** consumer on the runtime-owned `SessionMemoryEntity`
watches `AiMessageAdded` (the only event carrying the running size), a pure rule decides whether the
threshold is crossed, a **Scala** summariser agent writes the summary, and **one Java class** performs the
three calls that need a method reference. Compaction is **verified by re-reading**, because a stale write
is accepted silently. Applies to every session in the service, so capabilities 4, 6 and 14 gain the bound
without being edited.

**The motivation changed during Phase 0 and the plan reflects the corrected one** (research S-1/S-2):
history is already bounded at 510 KiB and that bound is turn-aligned and safe, so this is a **cost and
continuity** feature — 510 KiB is ~100k+ tokens re-sent per turn, and eviction discards the oldest turns
leaving nothing behind — not the bug fix the spec was first written on.

## Technical Context

**Language/Version**: Scala 3.3.8 (LTS) on the Java-first Akka Java SDK; Java 21 for the one quarantined class
**Primary Dependencies**: `akka-javasdk` 3.6.3 only — **no new dependency**
**Storage**: none of ours. The history lives in the runtime-owned `SessionMemoryEntity`; our own state is
in-process and bounded (one atomic cell), lost on restart by the same reasoning as capabilities 15 and 16
**Testing**: JUnit 5 + AssertJ + Awaitility. `TestModelProvider` for the summariser; **no live model**.
Recall *through* the model across compaction is **live-only** (capabilities 4 and 6 both measured that a
mocked model receives only the current turn)
**Target Platform**: the existing service — one consumer, one agent, one Java gateway, one read endpoint
**Project Type**: single Akka service (Scala sources with a deliberate, pinned Java quarantine)
**Performance Goals**: the common case (below threshold) costs **zero entity reads** — the size arrives on
the event (S-5). Compaction itself is one model call, off the user's turn
**Constraints**: threshold in bytes, configurable, disableable, and **range-enforced below 510 KiB** (S-1);
an out-of-range value is the **server's** fault, not the caller's (capability 15, limitations §7d); no
`readLast(N)` anywhere (FR-010); capability 6 untouched (FR-015); capabilities 4, 6 and 14 pass their
existing tests unmodified (SC-009); idiomatic Scala with **no mutable state** — one `AtomicReference` over
an immutable value with pure transitions (the capability 15 review rule)
**Scale/Scope**: every session in the service (FR-014). Bounded observability retention, as in capability 16

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1 — still passing.*

| Principle | Assessment |
|---|---|
| **I. Akka SDK First** (non-negotiable) | ✅ Every part is an SDK primitive: a `Consumer`, an `Agent`, the `SessionMemoryEntity` the runtime owns, an HTTP endpoint. **No new dependency** — the SDK documents this whole mechanism (`akka-context/sdk/agents/memory.html.md` §Compaction). The one Java class is not a deviation from the SDK but a consequence of it: `withDetailedReply` and the entity client are method-reference-only (S-4, R-3), which Phase 0 measured rather than assumed. |
| **II. Design Principles** | ✅ **Domain independence**: the threshold rule, the summary-input formatting and the observability transitions are pure Scala with no Akka import, unit-tested with no runtime. **API isolation**: the read endpoint defines its own response types; `SessionHistory` never leaves the application layer. **Single responsibility**: trigger, summariser, gateway, store and endpoint are five small pieces, not one orchestrator. **Descriptive naming**: no `Event`, `Service` or `Manager`; names are `CompactionThreshold`, `SessionMemoryConsumer`, `ConversationSummary`. |
| **III. Test Coverage** | ✅ Pure rules unit-tested; wiring, the silent-stale-write hazard (R-4), the no-loop property (R-2) and the service-wide scope (SC-009) integration-tested offline. The one thing not offline-provable — recall *through* the model — is labelled live, not faked. |
| **IV. Simplicity** | ✅ No loop guard, because R-2 measured that the hazard does not fire. No archive of pre-compaction turns (an explicit non-goal). No entity of our own — the runtime already owns the history, and adding one would drag in the method-ref wall for no gain. The Java class exists only because a method reference must live somewhere. |

**No violations. Complexity Tracking section omitted.**

## Project Structure

### Documentation (this feature)

```text
specs/019-session-compaction/
├── spec.md              # premise corrected by Phase 0 (S-1/S-2)
├── research.md          # Phase 0: S-1..S-7 static, R-1..R-6 runtime, D1..D8 decisions
├── plan.md              # this file
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── compaction.md    # Phase 1 — the read surface and the compaction contract
└── checklists/
    └── requirements.md  # spec quality gate (passing)
```

### Source Code (repository root)

```text
# Capability 17 — Scala, with ONE Java class (measured, not chosen)
src/main/scala/com/gwgs/akkaagentic/compaction/domain/
    CompactionThreshold.scala     # pure: bytes, range-enforced BELOW the SDK's 510 KiB (S-1)
    CompactionDecision.scala      # pure: (historySize, threshold, enabled) => Compact | Leave
    HistoryLine.scala             # pure: the neutral conversation type, so `domain` imports no Akka
    SummaryRequest.scala          # pure: HistoryLine list -> the text a summariser reads
    CompactionLedger.scala        # pure: per-session record + bounded retention, (Ledger, Result) transitions

src/main/scala/com/gwgs/akkaagentic/compaction/application/
    SessionMemoryConsumer.scala   # TRIGGER: @Consume.FromEventSourcedEntity(SessionMemoryEntity), AiMessageAdded only
    CompactionAgent.scala         # the summariser; Scala Agent, Java-shaped result
    ConversationSummary.scala     # the Java-shaped result (here, not domain — it carries @Description)
    CompactionStore.scala         # ONE AtomicReference over CompactionLedger, pure CAS transitions
    CompactionSettings.scala      # threshold + enabled from Config; ConfigException.BadValue, never `require`

src/main/java/com/gwgs/akkaagentic/compaction/application/
    SessionMemoryGateway.java     # THE ONE JAVA CLASS — getHistory, compactHistory, and the
                                  # .method(CompactionAgent::summarize).withDetailedReply() call (R-3, S-4)

src/main/scala/com/gwgs/akkaagentic/compaction/api/
    CompactionEndpoint.scala      # GET /compaction, GET /compaction/{sessionId} — read-only

src/test/scala/com/gwgs/akkaagentic/compaction/domain/        # pure unit tests, no runtime
src/test/scala/com/gwgs/akkaagentic/compaction/application/   # trigger, no-loop (R-2), stale write (R-4)
src/test/scala/com/gwgs/akkaagentic/compaction/api/           # the read surface
src/test/scala/com/gwgs/akkaagentic/compaction/OneJavaClassTest.scala   # pins the quarantine at exactly one
src/main/scala/com/gwgs/akkaagentic/compaction/probe/         # Phase 0 evidence, trimmed before merge
src/main/java/com/gwgs/akkaagentic/compaction/probe/
```

**Structure Decision**: the established `domain` / `application` / `api` split per capability, with the
Java quarantine visible in the tree exactly as capabilities 14 and 15 make theirs visible — one file under
`src/main/java`, and a test that fails if a second appears. Phase 0's probes live under `probe/` and are
trimmed to the evidence nothing else carries, as capability 16 did.

**Descriptor changes** (`src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`):
add `SessionMemoryConsumer` under `consumer`, `CompactionAgent` under `agent`, `CompactionEndpoint` under
`http-endpoint`. **`SessionMemoryEntity` is NOT added** — the runtime owns it, exactly as in capability 4.
`SessionMemoryGateway`, the settings and the store are not components.

## How the pieces fit

```text
a turn completes
      │
      ▼  AiMessageAdded(historySizeInBytes)        ← the ONLY event carrying it (S-5),
SessionMemoryConsumer  [Scala]                       so no entity read, and never mid-turn
      │
      ├── CompactionDecision.decide(size, threshold, enabled)   [pure]
      │        └── Leave  → effects().done()        ← the common case costs nothing
      ▼ Compact
SessionMemoryGateway  [Java — the one class]
      ├── getHistory(sessionId)                    → SessionHistory + sequenceNumber
      ├── .method(CompactionAgent::summarize).withDetailedReply()   → summary + token usage
      ├── compactHistory(CompactionCmd(user, ai, sequenceNumber))
      └── getHistory(sessionId) AGAIN              ← R-4: a stale write is accepted SILENTLY,
      │                                              so success is verified, never assumed
      ▼
CompactionStore  [Scala, one atomic cell]  →  GET /compaction  [Scala]
```

## Phase 2 note

`/akka.tasks` generates `tasks.md`. Sequencing follows the spec's priorities: the pure domain first, then
the trigger and the gateway (US1), then what survives compaction (US2), then the failure and concurrency
behaviour (US3, carrying R-4's verified-not-assumed rule and R-5's unmeasured streaming race), then the
operable threshold (US4), then the documentation (US5). Each gate is a commit, per CLAUDE.md.
