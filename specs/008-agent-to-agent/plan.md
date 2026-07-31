# Implementation Plan: Agent-to-agent delegation (personal assistants)

**Branch**: `008-agent-to-agent` | **Date**: 2026-07-31 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/008-agent-to-agent/spec.md`

## Summary

Capability 6 is a **personal assistant per username** that holds a persisted multi-turn conversation and
a persisted to-do list, and can **delegate** a request to another user's assistant. `POST
/request/{username}` is answered **synchronously**: a `PersonalAssistantAgent` runs with the username as
its session id (so chat history is replayed from the SDK's session memory), exposes to-do tools and — for
top-level requests only — a **forward tool** that invokes another user's assistant and relays the reply.

The **load-bearing planning finding is a two-sided interop result**, and it is the reason this capability
exists:

1. **Agent-to-agent delegation is idiomatic Scala.** One Scala agent invokes another through the agent
   `ComponentClient`, which has the `dynamicCall(id)` escape hatch (feature-002). There is **no
   method-reference wall** on the agent client — the Scala forward tool compiles to exactly what the SDK
   expects. (research.md R1)
2. **But a per-user *mutable* to-do list re-hits the entity wall.** A to-do list is structured, mutable
   domain state → a `KeyValueEntity`; the entity client is `.method(Entity::cmd)`-only with **no
   `dynamicCall`** (feature-006 R6). So the to-do subsystem is **quarantined into Java** — a Java
   `TodoEntity` plus a Java `TodoTools` **tool object** handed to the Scala agent via `.tools(...)` — while
   the agent, forward tool, and endpoint stay Scala. This "tool-object seam" is the design's key move.
   Its direct consequence is **FR-009**: no direct to-do read endpoint, because a Scala endpoint reading
   the entity would re-hit the same wall — to-dos are surfaced only through the assistant. (research.md R2)

Two further decisions follow from the synchronous surface and the two-mapper boundary:

- **Sync + retry, not durable-async** (research.md R3): a request-based agent call is synchronous and not
  durable mid-flight. Chat history and to-dos survive a restart (both persisted), but an in-flight
  delegation is lost and the caller retries. The durable-async alternative (an `AutonomousAgent` task with
  a start-then-poll surface, like cap-3/cap-5) is rejected **for this capability** — it would trade the
  synchronous UX for polling, and the sync limit is itself the teaching point.
- **Two-layer request + structural one-hop guard** (research.md R4): the HTTP body is an idiomatic
  `Option`-typed Scala DTO (`{message}`), while the internal agent wire `Request(username, message,
  delegated)` is Java-shaped (it crosses the SDK's internal mapper — feature-003 two-mapper). `delegated`
  defaults `false` and is set `true` **only** by the forward tool; the agent offers the forward tool only
  when `!delegated`, so a delegate **structurally cannot** re-delegate. The API caller never sees or sets
  `delegated`.

Chat-history tokens are bounded with `MemoryProvider.limitedWindow().readLast(N)` (research.md R5).
Capabilities 1–5 are untouched. The mixed Java/Scala build needs **no `pom.xml` change** — the
feature-004 recipe (`-proc:none`, `-parameters`, `sendJavaToScalac=false`) already covers it.

## Technical Context

**Language/Version**: Scala 3 (agent, forward tool, endpoint, request validation) + Java 21 (to-do entity,
to-do tool object, to-do domain records) on the Java-first Akka SDK 3.x.
**Primary Dependencies**: Akka SDK (`akka.javasdk.agent.Agent`, `keyvalueentity.KeyValueEntity`,
`agent.MemoryProvider`, `client.ComponentClient`, `http.*`); `DefaultScalaModule` (already registered via
`Bootstrap`). No new dependencies.
**Storage**: SDK durable store — SDK-internal `SessionMemoryEntity` (chat history, runtime-registered) and
our `TodoEntity` `KeyValueEntity` (to-dos). Both keyed by username. Local dev uses in-memory unless the
on-disk (H2) flag is set.
**Testing**: `TestModelProvider` (offline, deterministic), `KeyValueEntityTestKit`, `TestKitSupport` +
`httpClient` for endpoint integration. No API key / network for `mvn verify`.
**Target Platform**: Local Akka runtime (`exec:java`), default model local Ollama `qwen3:8b`.
**Project Type**: Single Akka service (web-service), mixed Java/Scala module.
**Performance Goals**: N/A (LLM-bound; not throughput-sensitive). Synchronous request/response.
**Constraints**: Bounded chat-history window (`readLast(N)`); one-hop delegation; no new `pom.xml`
settings; capabilities 1–5 stay green.
**Scale/Scope**: One new capability — 1 agent, 1 entity, 2 tool objects, 1 endpoint, ~3 domain types,
5 test classes. Package base `com.gwgs.akkaagentic.a2a`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

- **I. Akka SDK First (NON-NEGOTIABLE)** — ✅ PASS. Every component is an Akka SDK primitive: request-based
  `Agent`, `KeyValueEntity`, HTTP `@HttpEndpoint`, SDK session memory. Delegation uses the SDK
  `ComponentClient`; to-do tools are SDK `@FunctionTool`s. **No new external dependency.** The Java
  quarantine is still 100% Akka SDK — it is a *language* boundary, not a framework escape.
- **II. Design Principles** — ✅ PASS. *Domain independence*: `Todo`/`TodoList`/`AssistantRequest` are pure,
  Akka-free, unit-testable. *API isolation*: the endpoint's `{message}` / `{username, reply}` DTOs are
  distinct from the internal agent `Request`. *Single responsibility*: agent (converse+route), entity
  (to-do state), tool objects (to-do ops / delegation) are separate. *Descriptive naming*:
  `PersonalAssistantAgent`, `TodoEntity`, `ForwardTool`, `TodoTools` — no generic `Manager`/`Service`.
- **III. Test Coverage** — ✅ PASS. Explicit test phases: `TodoList` unit (Java), `TodoEntity`
  `KeyValueEntityTestKit` (Java), agent `TestModelProvider` incl. loop guard (Scala), endpoint integration
  incl. A→B delegation and 400 validation (Scala). Recall proven live (documented mock caveat).
- **IV. Simplicity** — ✅ PASS. **No Workflow, no Autonomous Agent, no Entity of our own beyond the to-do
  store, and no case→task mapping store** — the username *is* the key for both memory and to-dos, so the
  endpoint stores nothing. YAGNI honored: sync surface (no polling machinery), one-hop guard (no depth
  counter), `readLast(N)` (no compaction subsystem).

**Justified deviation** (see Complexity Tracking): the capability mixes Java into a Scala module. This is
**forced by the SDK**, not a design choice, and is confined to the to-do subsystem behind a tool-object
seam.

## Project Structure

### Documentation (this feature)

```text
specs/008-agent-to-agent/
├── plan.md              # This file
├── research.md          # Phase 0 — the two-sided interop finding (R1–R6)
├── data-model.md        # Phase 1 — domain + wire types + boundary table
├── quickstart.md        # Phase 1 — curl walkthrough (own todos + delegation)
├── contracts/           # Phase 1 — POST /request/{username} contract
│   └── personal-assistant-endpoint.md
└── checklists/
    └── requirements.md  # from /akka.specify (passing)
```

### Source Code (repository root)

```text
# Capability 6 — package base com.gwgs.akkaagentic.a2a  (Scala + quarantined Java)
src/main/scala/com/gwgs/akkaagentic/a2a/domain/        # AssistantRequest (validation)          [Scala]
src/main/java/com/gwgs/akkaagentic/a2a/domain/          # Todo, TodoList (entity state + logic)   [Java]
src/main/scala/com/gwgs/akkaagentic/a2a/application/    # PersonalAssistantAgent (+ Request), ForwardTool [Scala]
src/main/java/com/gwgs/akkaagentic/a2a/application/      # TodoEntity, TodoTools                   [Java]
src/main/scala/com/gwgs/akkaagentic/a2a/api/            # PersonalAssistantEndpoint               [Scala]

src/test/java/com/gwgs/akkaagentic/a2a/...              # TodoListTest, TodoEntityTest            [Java]
src/test/scala/com/gwgs/akkaagentic/a2a/...             # PersonalAssistantAgentTest, PersonalAssistantEndpointIntegrationTest [Scala]

src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
  # + agent: PersonalAssistantAgent ; + NEW key key-value-entity: TodoEntity ; + http-endpoint: PersonalAssistantEndpoint
```

**Structure Decision**: Single Akka service, extended with the `a2a` package. Language is split by the
interop wall, not by layer: everything is Scala **except** the to-do subsystem (entity + tool object +
its two domain records), which is Java because the `KeyValueEntity` client is un-callable from Scala. The
split is confined behind `.tools(new TodoTools(componentClient))` so the agent stays Scala. `ForwardTool`
and `TodoTools` are **tool objects, not components** (absent from the descriptor). Reuses the existing
mixed-build `pom.xml` unchanged.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Mixed Java in a Scala capability (to-do `TodoEntity` + `TodoTools`) | A per-user mutable to-do list is structured durable state → a `KeyValueEntity`, whose client is **method-reference-only** (no `dynamicCall`) and therefore un-callable from Scala (feature-006 R6). | *All-Scala* rejected: a Scala agent/tool cannot invoke a `KeyValueEntity`; there is no `dynamicCall` escape hatch on the entity client. *Store to-dos in session memory* rejected: it is an opaque append-only transcript, not queryable/mutable structured state. The Java is minimized and quarantined behind a tool-object seam; the agent, delegation, and endpoint stay Scala. |
| Two request types (HTTP `{message}` DTO **and** internal Java-shaped `Request`) | The SDK uses **two Jackson mappers** (feature-003): HTTP bodies go through the Scala-aware mapper (idiomatic `Option`), but component payloads cross a separate internal mapper that cannot read Scala `Option` — so the agent wire type must be Java-shaped. | A *single idiomatic type everywhere* rejected: an annotation-free `Option` type fails the internal mapper at runtime ("Cannot construct instance of `scala.Option`"). The split is the established project boundary, reused here. |
