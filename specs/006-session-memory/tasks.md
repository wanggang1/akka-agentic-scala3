# Tasks: Session memory (multi-turn chat)

**Input**: Design documents from `/specs/006-session-memory/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/chat-api.md, quickstart.md

**Tests**: INCLUDED — Constitution III (test coverage) and the project's per-component build rhythm
(CLAUDE.md) both require a test alongside each component.

**Organization**: By user story. US1 (multi-turn) is the MVP and carries all the shared code
(`ChatMessage` is foundational; `ChatAgent`, `ChatEndpoint`, descriptor wiring land in US1). US2
(isolation) and US3 (validation) add **only tests** over that same code — no new production files —
because the SDK's session memory delivers isolation for free and validation lives in code built in US1.

## Path Conventions

Scala sources under `src/main/scala/com/gwgs/akkaagentic/chat/{domain,application,api}`; tests under the
mirrored `src/test/scala/...`. Single Akka service, mixed Scala/Java module (this capability is Scala).

---

## Phase 1: Setup

**Purpose**: Confirm a green baseline before adding code.

- [x] T001 Confirm branch `006-session-memory` and run `mvn verify` to establish that capabilities 1–3 are green before any change (SC-006 baseline). ✅ green.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The domain validator every story's request path depends on.

**⚠️ CRITICAL**: US1–US3 all gate input through `ChatMessage`; build it first.

- [x] T002 [P] Create `ChatMessage` domain validator in `src/main/scala/com/gwgs/akkaagentic/chat/domain/ChatMessage.scala` — `final case class ChatMessage(text: String)` + `object ChatMessage { def validate(raw: Option[String]): Either[String, ChatMessage] }` (trim, blank/absent → `Left("message must not be blank")`, else `Right`). No Akka imports (Constitution II).
- [x] T003 [P] Create `ChatMessageTest` in `src/test/scala/com/gwgs/akkaagentic/chat/domain/ChatMessageTest.scala` — assert `None`, blank, and whitespace-only → `Left`; a normal and a pad-trimmed message → `Right` with trimmed `text`.
- [x] T004 Run `mvn test` to verify the domain layer compiles and `ChatMessageTest` passes. ✅ 3 tests green.

**Checkpoint**: Domain ready — user-story work can begin.

---

## Phase 3: User Story 1 - Multi-turn conversation remembers earlier turns (Priority: P1) 🎯 MVP

**Goal**: A conversation on one session id recalls earlier turns — state a fact, then ask about it on the
same id and get it back.

**Independent Test**: Two requests on session `C`: "my name is Ada" then "what is my name?" → the second
reply references "Ada".

- [x] T005 [US1] Create `ChatAgent` in `src/main/scala/com/gwgs/akkaagentic/chat/application/ChatAgent.scala` — `@Component(id = "chat-agent")` extends `Agent`; single handler `def chat(message: String): Agent.Effect[String]` with a concise conversational-assistant `systemMessage`, `.memory(MemoryProvider.limitedWindow())` (research R5), `.userMessage(message).thenReply()`. Stateless; no command record (bare `String`, research R4).
- [x] T006 [US1] Register the agent: add `com.gwgs.akkaagentic.chat.application.ChatAgent` under the `agent` key in `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`. Do NOT add `SessionMemoryEntity` (research R3).
- [x] T007 [US1] Create `ChatEndpoint` in `src/main/scala/com/gwgs/akkaagentic/chat/api/ChatEndpoint.scala` — `@HttpEndpoint` + `@Acl(INTERNET)`; idiomatic DTOs `ChatRequest(message: Option[String])` (`@JsonIgnoreProperties(ignoreUnknown = true)`) and `ChatReply(sessionId, reply)`; `@Post("/chat/{sessionId}")` validates via `ChatMessage.validate` (→ `badRequest`), else `componentClient.forAgent().inSession(sessionId).dynamicCall[String, String]("chat-agent").invoke(text)` → `ok(ChatReply(sessionId, reply))`.
- [x] T008 [US1] Register the endpoint: add `com.gwgs.akkaagentic.chat.api.ChatEndpoint` under the `http-endpoint` key in the descriptor. Run `mvn compile`.
- [x] T009 [US1] Create `ChatAgentIntegrationTest`. **Resolved research R6** (measured with `withMessageSelector`): the mock receives ONLY the current turn (size-1 message list; no history, no system prompt; not a race). So recall is NOT offline-observable through the mock. Per the chosen approach (Scala-only, lean on live test), the test asserts the `dynamicCall`+`.inSession` wiring/per-turn reply and **pins the finding as a regression** (`containsExactly("my name is Ada", "what is my name?")`). Recall itself → live smoke test (T018). ✅ 2 tests green.
- [x] T010 [US1] Run `mvn verify`; confirm US1 tests pass and capabilities 1–3 remain green. ✅ 26 tests green, BUILD SUCCESS.

**Checkpoint**: MVP wiring in place; multi-turn *recall* is verified live (T018), not through the offline mock (R6).

---

## Phase 4: User Story 2 - Conversations are isolated (Priority: P2)

**Goal**: A fact stated on one session id never surfaces on a different id.

**Independent Test**: State "my name is Ada" on `C1`, ask "what is my name?" on `C2` → `C2` does not
know the name.

- [ ] T011 [US2] Isolation is **not offline-observable through the mock** (R6: the mock never sees any session's history, so it can't show cross-session leakage or its absence). Offline, add a wiring assertion to `ChatAgentIntegrationTest` that two *different* session ids each independently return their reply (the per-session plumbing). The authoritative isolation proof is the **live smoke test (T018)**. No production code changes (isolation is intrinsic to per-id session memory, research R1).
- [ ] T012 [US2] Run `mvn verify` to confirm US1 + US2 wiring tests pass together.

**Checkpoint**: Per-session wiring green; isolation *behavior* verified live (T018).

---

## Phase 5: User Story 3 - Invalid input rejected before the assistant (Priority: P3)

**Goal**: Blank/absent/malformed input is turned away with `400` and no model call; unknown props tolerated.

**Independent Test**: Blank message → `400`; malformed body → `400`; extra field + valid message → `200`.

- [ ] T013 [US3] Create `ChatEndpointIntegrationTest` in `src/test/scala/com/gwgs/akkaagentic/chat/api/ChatEndpointIntegrationTest.scala` (HTTP layer via `httpClient`) covering the offline-provable contract: C7 a `200` reply echoes the path `sessionId` and carries the mocked reply (US1 wiring), C3 blank → 400, C4 absent field → 400, C5 malformed JSON → 400, C6 valid + unknown prop → 200 (US3). Use `TestModelProvider` for the agent; assert 400 cases via omitting `responseBodyAs` (per the httpClient failure-status pattern). (C1 recall / C2 isolation are memory-behavior → live smoke test T018, not asserted here — R6.)
- [ ] T014 [US3] Run `mvn verify` to confirm the full contract passes and capabilities 1–3 remain green.

**Checkpoint**: All three stories independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Docs, the interop finding, and live verification.

- [ ] T015 [P] Update `README.md` — add the cap-4 project-layout block (`chat` package), a "Scala interop notes §6" entry (session memory: Scala-friendly to *use* — string-keyed, builder-based API, runtime-owned memory entity, bare-`String` payload — but its effect is invisible to the offline `TestModelProvider`, and the EventSourcedEntity client's method-ref wall blocks the Scala-only offline workaround; behavior verified live — research R6), and a "Capability 4 — multi-turn chat (`POST /chat/{sessionId}`)" section with curl examples from `contracts/chat-api.md`.
- [ ] T016 [P] Update `ROADMAP.md` — flip capability 4 row to done, update "You are here", and add a short note: no new wall to *build* session memory on Scala, but one new *testing* limitation (mock can't observe memory; entity-client method-ref wall blocks the Scala offline retention check).
- [ ] T017 [P] Add a memory file `akka-scala-session-memory-testkit-blind.md` (+ MEMORY.md index line) capturing R6: multi-turn session memory is Scala-friendly to use (no descriptor entry for `SessionMemoryEntity`, bare-`String` payload), but `TestModelProvider` receives only the current turn so memory behavior isn't offline-provable through the mock, and the EventSourcedEntity client has no `dynamicCall` (method-ref wall) so the Scala offline retention check is blocked — verify memory behavior live. Link `[[akka-scala-workflow-methodref-wall]]`, `[[gemini-tools-vs-structured-output]]` neighbors.
- [ ] T018 Live smoke test against Gemini (**authoritative proof of US1 recall + US2 isolation**): `set -a && source .env && set +a && mvn compile exec:java`; run the US1 two-turn curl on one session id and confirm the second reply recalls the first, plus the US2 isolation curl on a different id. Record the outcome.
- [ ] T019 Final `mvn verify` (offline suite green across all four capabilities) and confirm `.env` remains git-ignored before any commit.

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T004)** → **US1 (T005–T010)** → then US2 (T011–T012) and US3 (T013–T014), which depend only on US1's code → **Polish (T015–T019)**.
- Within US1: `ChatAgent` (T005) + descriptor (T006) before the endpoint (T007–T008); all production code before the integration test (T009).
- US2 and US3 add tests only; they can be authored in parallel once US1 is complete, but each ends with its own `mvn verify`.

### Parallel Opportunities

- T002 ∥ T003 (domain code and its test are different files).
- T015 ∥ T016 ∥ T017 (docs and memory file are independent).

---

## Implementation Strategy

- **MVP = Phase 1 + 2 + 3 (US1).** Stop and validate multi-turn recall before adding US2/US3.
- **Incremental**: US2 and US3 layer tests over the MVP without touching production code — each a small,
  independently green increment.
- **Per-component gates** (CLAUDE.md): build one component + its test at a time, pausing for review
  between steps; commit after each logical group; never commit `.env`.

## Notes

- No `pom.xml` change (the mixed Scala/Java build already compiles new Scala sources — research/plan).
- Research R6 (does `TestModelProvider.whenMessage` see replayed history?) is resolved in T009 and its
  outcome recorded; the live smoke test (T018) is the independent recall proof either way.
- Total: 19 tasks — Setup 1, Foundational 3, US1 6, US2 2, US3 2, Polish 5.
