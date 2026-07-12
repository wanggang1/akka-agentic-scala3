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

**Finding (measured, `ChatAgentIntegrationTest`)**: the mock receives **only the current turn's user
message** — no replayed history, and not even the system prompt. Instrumenting the model input via
`withMessageSelector(List<InputMessage> -> InputMessage)` across two turns on one session id showed each
call getting a **size-1** message list (`call[0]="my name is Ada"`, `call[1]="what is my name?"`). A 2s
gap between turns changed nothing, so it is **not** a write/read race — the SDK simply does not assemble
session history into a *test* model provider's input.

**Consequences**:
- Multi-turn **recall** (US1) and **isolation** (US2) are **not observable offline through the mock** —
  both depend on the model seeing (or not seeing) history, which the mock never does.
- The Scala escape hatch is blocked: proving **retention** by querying `SessionMemoryEntity` needs the
  **EventSourcedEntity client, which has no `dynamicCall`** (only Java `akka.japi.function.Function`
  method-refs — verified by `javap`). That is the same method-ref wall as cap-2's `WorkflowClient`
  (`[[akka-scala-workflow-methodref-wall]]`), so an offline retention check would require **Java** in
  this otherwise pure-Scala capability.

**Decision (Scala-only, lean on the live test)**:
- **Offline** (`TestModelProvider`, `httpClient`): assert the `dynamicCall`+`.inSession` wiring, per-turn
  replies, input validation, and the full HTTP contract; and **pin the finding as a regression** —
  a test asserting the mock sees exactly the current turn (so a future SDK that starts replaying history
  to test providers would flip it and prompt us to strengthen the offline suite).
- **Live** (Gemini smoke test, tasks T018): the authoritative proof of recall (US1) and isolation (US2).
- Spec `SC-001`/`SC-002` were reworded accordingly (live verification + offline-limitation note).

**Why this is the right call**: it keeps cap-4 100% Scala (dragging Java in *only* to observe a test-only
limitation would contradict the capability's point and add complexity — Constitution IV). The runtime
behavior is unchanged; only the *locus* of each guarantee moves. This nuances the headline below: session
memory is Scala-friendly to **use**, but its effect is **invisible to the offline mock**, and the
entity-client method-ref wall blocks the Scala-only offline workaround.

## Summary of decisions

| # | Topic | Decision |
|---|-------|----------|
| R1 | Memory storage/replay | SDK session memory, keyed by `.inSession(id)`; no custom storage |
| R2 | Call path | `dynamicCall("chat-agent")` + caller-supplied `sessionId`; no method-ref wall |
| R3 | Descriptor | `chat-agent` (agent) + `ChatEndpoint` (http-endpoint) only; memory entity is runtime-registered |
| R4 | Wire types | `String` agent payload (no Java-shaped type) + idiomatic `Option` HTTP DTOs |
| R5 | MemoryProvider | Explicit `MemoryProvider.limitedWindow()`; no `readLast(N)` |
| R6 | Offline memory proof | RESOLVED: the mock sees only the current turn; recall/isolation move to the live smoke test; finding pinned as a regression |

**Headline finding (refined by R6)**: session memory is **Scala-friendly to *use*** — string-keyed via
`.inSession(id)`, builder-based `MemoryProvider` API (no method-ref wall), a runtime-owned backing entity
(no descriptor entry), and a bare-`String` payload (no Java-shaped type). But its **effect is invisible to
the offline mock** (`TestModelProvider` receives only the current turn), and the Scala-only offline
workaround is blocked by the EventSourcedEntity client's method-ref wall (no `dynamicCall`). So the
capability's memory *behavior* is verified live, while the offline suite covers wiring, validation, the
HTTP contract, and pins the mock's blindness as a regression. Net: no new friction to *build* on Scala,
one new limitation to *test* on Scala.
