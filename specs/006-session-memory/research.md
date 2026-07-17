# Research: Session memory (multi-turn chat)

Phase 0 findings. Each item resolves an unknown from the plan's Technical Context or records a decision
that shapes the design. The through-line question for every capability in this project is *"what Scala-
on-Java-SDK friction appears?"* — for capability 4 the expected (and, per these findings, likely) answer
is **none new**.

## R1 — How session memory is keyed, stored, and replayed

**Decision**: Rely on the SDK's built-in session memory, keyed by the conversation id passed to
`.inSession(sessionId)`. Do not implement any storage.

**Rationale** (`akka-context/sdk/agents/memory.html.md`):
- On every agent interaction the SDK **automatically stores both the user message and the AI reply** in
  session memory, and **includes them as context in subsequent requests** with the same session id.
- Session memory is **identified by a session id**, **shared** between agents that use the same id,
  **persisted as an event-sourced entity**, and **automatically managed** by the Agent.
- It is **enabled by default** for all agents.

So multi-turn memory (FR-002, FR-003) and isolation (FR-004) are intrinsic to the platform: two
different ids are two different session-memory entities. A previously unseen id (FR-005) is simply an
empty history — not an error.

**Alternatives considered**: A custom entity to store turns ourselves (rejected — reinvents the SDK's
`SessionMemoryEntity`, violates Constitution I & IV). `MemoryProvider.custom()` with an external store
(rejected — no requirement for external storage; adds a dependency).

## R2 — Session id contract: `dynamicCall` + `.inSession`, no method-ref wall

**Decision**: Call the Scala `ChatAgent` exactly as cap-1/cap-3 call their agents —
`componentClient.forAgent().inSession(sessionId).dynamicCall[String, String]("chat-agent").invoke(msg)`
— using the **caller-supplied** `sessionId` (from the path) rather than a fresh UUID. Reusing the id
across requests is the whole feature.

**Rationale**: `.inSession(String)` already takes a plain string; nothing about session memory introduces
a Java method reference. The `MemoryProvider`/`MemoryFilter` API is entirely builder-based with
`String`/`int`/`Class` arguments (memory doc). This is the inverse of cap-2's Workflow wall and matches
cap-3's finding: **agents and their memory are Scala-friendly.** (Memory index: `[[akka-scala-akka-componentclient-dynamiccall]]`, `[[akka-scala-autonomous-agent-no-wall]]`.)

**Alternatives considered**: Java method-reference form `.method(ChatAgent::chat)` — rejected, a Scala
lambda's synthetic `$anonfun` name doesn't resolve through `SerializedLambda` (the established cap-1
finding). `dynamicCall` is the required Scala path.

## R3 — Descriptor: only `ChatAgent` + `ChatEndpoint`; `SessionMemoryEntity` is runtime-registered

**Decision**: Add exactly two lines to the hand-maintained descriptor — `chat-agent` under the `agent`
key and `ChatEndpoint` under `http-endpoint`. Do **not** add `SessionMemoryEntity`.

**Rationale**: The `SessionMemoryEntity` is an **SDK-internal** event-sourced entity that the runtime
registers itself; it is not one of *our* components and does not live in our package. Our hand-maintained
descriptor exists only because the annotation processor is disabled (`-proc:none`) and therefore cannot
discover *our* Scala components — it has never been responsible for SDK-internal components, which the
runtime wires up regardless. **Expected observation**: memory works end-to-end with no descriptor entry
for the memory entity. (To be confirmed by the live smoke test; a failure here would be a genuinely new
finding.) (Memory index: `[[scala-akka-component-descriptor]]`.)

**Alternatives considered**: none — we cannot list a class we don't own, and there is no need to.

## R4 — Wire types: `String` payload + idiomatic `Option` DTOs (no Java-shaped type at all)

**Decision**: `ChatAgent.chat` is `chat(message: String): Effect[String]` — a plain `String` command and
`String` reply. The endpoint DTOs (`ChatRequest`, `ChatReply`) are **idiomatic Scala** case classes with
`Option`/`List` fields (feature 003 pattern).

**Rationale**: The two-mapper finding (feature 003) only bites when a **non-trivial** component payload
must cross the SDK's *internal* Jackson mapper. A bare `String` serializes trivially through any mapper,
so — unlike cap-1's `GreetingAgent.Result` and cap-3's `HelpAnswer` — capability 4 needs **no
Jackson-annotated, Java-shaped type**. The only JSON types are the HTTP request/response bodies, which go
through the *public* mapper (Scala module registered by `Bootstrap`) and are therefore idiomatic. Net:
this is the least-interop capability in the project. (Memory index: `[[scala-jackson-module-followup]]`.)

**Alternatives considered**: A structured `{reply, sessionId}` agent result crossing the internal mapper
(rejected — unnecessary; the endpoint composes `sessionId` from the path and `reply` from the `String`
return, keeping the agent payload trivial).

## R5 — MemoryProvider: explicit `limitedWindow()` vs. rely on the default

**Decision**: Set it **explicitly** in the agent: `.memory(MemoryProvider.limitedWindow())`.

**Rationale**: Functionally identical to the default (memory is on, `limited-window` is the default
policy), but this is a *learning* capability whose entire subject is session memory — naming it in the
agent's one effect chain makes the code self-documenting about what is being demonstrated, at the cost of
a single fluent call (no added complexity, so no YAGNI tension). `readLast(N)` is deliberately **not**
used — bounding is left to the default size window (FR-009); a turn-count cap is unnecessary for the demo
and would risk dropping the very fact US1 asks the model to recall.

**Alternatives considered**: Rely on the implicit default (rejected only on pedagogical grounds — the
demo reads better when memory is explicit). `MemoryProvider.none()` (rejected — that disables the
feature). `readLast(k)` (rejected — YAGNI; risks evicting the fact under test).

## R6 — Offline proof of multi-turn memory with `TestModelProvider` (RESOLVED during build)

**Question**: When the runtime replays a conversation's history to the model, does a
`TestModelProvider` mock receive that **accumulated history** (so a turn-2 assertion can match on the
fact from turn 1), or only the **latest user message**?

**Finding, part 1 — what the mock sees (measured, `ChatAgentIntegrationTest`)**: the mock receives
**only the current turn's user message** — no replayed history, and not even the system prompt.
Instrumenting the model input via `withMessageSelector(List<InputMessage> -> InputMessage)` across two
turns on one session id showed each call getting a **size-1** message list (`call[0]="my name is Ada"`,
`call[1]="what is my name?"`). A 2s gap between turns changed nothing, so it is **not** a write/read
race.

**Finding, part 2 — is memory actually written? (measured, `SessionMemoryIntegrationTest`)**: **yes.**
Reading the SDK-internal `SessionMemoryEntity` for the session id after two turns returns **4 stored
messages** (2 `UserMessage` + 2 `AiMessage`, `componentId = "chat-agent"`, `sequenceNumber = 5`); a
never-used session id returns an **empty** history. So the earlier "the SDK does not assemble history
into a test provider's input" was **too strong** — memory is written *and* readable offline; the mock's
size-1 view is purely a **provider-feed gap** (the assembled history isn't surfaced *into the
`TestModelProvider`*), not memory being off.

**Consequences (corrected)**:
- **Retention** (US1) and **isolation** (US2) **are** provable offline — by reading `SessionMemoryEntity`
  directly (turns stored under their id; a different id is independent).
- That read must be **Java**: the EventSourcedEntity client is method-reference-only
  (`SessionMemoryEntity::getHistory`) with **no `dynamicCall`** (verified by `javap`) — the same wall as
  cap-2's `WorkflowClient` (`[[akka-scala-workflow-methodref-wall]]`), so a Scala caller cannot query it.
- Only **recall** — the model *using* the replayed history in its answer — stays un-provable offline,
  because the mock ignores the history it isn't fed. That is the live smoke test's job.

**Decision (kept a small Java test; recall stays live)**:
- **Offline, Scala** (`ChatAgentIntegrationTest`, `httpClient`): `dynamicCall`+`.inSession` wiring,
  per-turn replies, validation, HTTP contract; plus a **regression pin** that the mock sees exactly the
  current turn (so a future SDK that starts feeding history to test providers would flip it).
- **Offline, Java** (`SessionMemoryIntegrationTest`): retention (US1) and isolation (US2) via the
  entity query. Java by necessity — matching the test language to a Java SDK entity Scala can't reach;
  the module is already mixed Scala/Java (cap-2), so this is principled, not a compromise
  (Constitution: "match the test to the code under test").
- **Live** (Gemini smoke test, tasks T018): the authoritative proof of **recall** (and an end-to-end
  re-confirmation of isolation).
- Spec `SC-001`/`SC-002` reworded accordingly.

This nuances the headline below: session memory is Scala-friendly to **use** *and* its effect is
**written/readable offline** — but observing it requires the Java entity client (method-ref wall), and
**recall** through the model is only visible against a real model.

## Summary of decisions

| # | Topic | Decision |
|---|-------|----------|
| R1 | Memory storage/replay | SDK session memory, keyed by `.inSession(id)`; no custom storage |
| R2 | Call path | `dynamicCall("chat-agent")` + caller-supplied `sessionId`; no method-ref wall |
| R3 | Descriptor | `chat-agent` (agent) + `ChatEndpoint` (http-endpoint) only; memory entity is runtime-registered |
| R4 | Wire types | `String` agent payload (no Java-shaped type) + idiomatic `Option` HTTP DTOs |
| R5 | MemoryProvider | Explicit `MemoryProvider.limitedWindow()`; no `readLast(N)` |
| R6 | Offline memory proof | RESOLVED: mock sees only the current turn, BUT memory is written+readable offline (proven by reading `SessionMemoryEntity`); retention+isolation covered by a Java entity-query test; recall stays live; mock-blindness pinned as a regression |

**Headline finding (refined by R6)**: session memory is **Scala-friendly to *use*** — string-keyed via
`.inSession(id)`, builder-based `MemoryProvider` API (no method-ref wall), a runtime-owned backing entity
(no descriptor entry), and a bare-`String` payload (no Java-shaped type). Its **effect is written and
readable offline** (the `SessionMemoryEntity` accumulates both turns), so retention and isolation are
offline-provable — but only by **reading the entity from Java**, because the EventSourcedEntity client is
method-reference-only with no `dynamicCall` (the cap-2 `WorkflowClient` wall again). The mocked model is
fed only the current turn, so **recall** (the model using history) is the one guarantee left to the live
test. Net: no new friction to *build* session memory on Scala; to *test* it, retention/isolation need the
Java entity client and recall needs a real model.
