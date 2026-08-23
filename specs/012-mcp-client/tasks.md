# Tasks: MCP Client Agent (cap-10)

**Input**: Design documents from `specs/012-mcp-client/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/http-grounded-ask.md

**Tests**: INCLUDED — this sandbox's convention is offline `mvn verify` + a live smoke; the spec's
Success Criteria (SC-004/005/006) are test-shaped.

**Organization**: A single `POST /grounded-ask` endpoint + one agent serve all three user stories, so
the shared build (spikes + agent + endpoint + descriptor) is **Foundational** (Phase 2); each user
story phase then adds its own test slice. Follows CLAUDE.md's incremental flow — build one component +
its test at a time, STOP for approval between major steps.

## Path Conventions

New Scala capability package `com.gwgs.akkaagentic.mcpclient` (`application` + `api`), parallel to
cap-9's `mcp`. No new `domain` — validation reuses cap-8's `docs.domain.AskQuestion`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Package scaffolding and descriptor entries.

- [x] T001 Create the cap-10 package directories: `src/main/scala/com/gwgs/akkaagentic/mcpclient/application/`, `src/main/scala/com/gwgs/akkaagentic/mcpclient/api/`, and `src/test/scala/com/gwgs/akkaagentic/mcpclient/api/`.
- [x] T002 Add cap-10 components to the hand-maintained descriptor `src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf`: `McpClientAgent` under `agent`, `McpClientEndpoint` under `http-endpoint`. (Leave the values pointing at the FQCNs to be created in Phase 2; `KnowledgeMcpEndpoint` stays under `mcp-endpoint` from cap-9.)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Settle the two residual mechanics with front-loaded spikes (mirroring cap-9's T004/T005),
then build the shared agent + endpoint. **No user-story test can pass until this phase is complete.**

**⚠️ CRITICAL**: The spikes decide (a) how the self-call is wired and (b) whether the tool loop is
offline-testable — both change how the US1/US3 tests are written.

- [x] T003 **S1 — topology spike.** ✅ RESOLVED: `fromService("akka-agentic-scala3")` (+ `withServiceName`) resolves to self's in-process `/mcp`; cap-9's INTERNET-only `@Acl` allows the self-service call — **no ACL edit, cap-9 untouched**. (`McpClientSpikeTest`, green.) In a scratch/throwaway test, verify `RemoteMcpTools.fromService("akka-agentic-scala3")` (with `TestKit.Settings.withServiceName("akka-agentic-scala3")`) lets an agent reach this service's own in-process `KnowledgeMcpEndpoint` `/mcp`. Settle the ACL: if the self-service principal is denied by cap-9's INTERNET-only `@Acl`, **additively broaden** `KnowledgeMcpEndpoint`'s `@Acl` to also allow the calling service (e.g. add a `service = "*"` matcher; keep `INTERNET`) in `src/main/scala/com/gwgs/akkaagentic/mcp/api/KnowledgeMcpEndpoint.scala`. Record the outcome (resolves? ACL edit needed?) in research.md R2. **Fallback if self-`fromService` doesn't resolve**: switch the decision to `RemoteMcpTools.fromServer(<base-url>)` and note how the base URL is obtained (TestKit bound URL / config).
- [x] T004 **S2 — tool-loop testability spike.** ✅ RESOLVED: a `TestModelProvider`-scripted `retrieve` call drives the REAL round-trip to the in-process `/mcp`; the received `ToolResult.content()` equals `KnowledgeStore.fromCorpus().retrieve(q, 3)` (SC-005 parity proven **offline**). Positive contrast to cap-7 D9 — no fallback needed. (`McpClientSpikeTest`, green.) In a scratch test, register a `TestModelProvider` for the agent and script a tool-calling turn: `whenUserMessage(...)` → `fixedResponse(ToolInvocationRequest("retrieve", {"question": ...}))`, then `whenToolResult(...)` → a final answer. Confirm the SDK performs the **real** remote-MCP round-trip to the in-process `/mcp` and that the received `ToolResult` text equals `KnowledgeStore.fromCorpus().retrieve(question, 3)` rendered the same way. Record in research.md R3 whether faithful offline testing holds. **Fallback ladder if it does not**: (1) `TestKit.Settings.withMockedHttpService("akka-agentic-scala3", fn)` serving canned JSON-RPC; (2) live-only for the closed loop, offline for validation/wiring/parity.
- [x] T005 (created during the spike) Implement `McpClientAgent` in `src/main/scala/com/gwgs/akkaagentic/mcpclient/application/McpClientAgent.scala`: a request-based `Agent` with one command handler taking a bare `String` question and returning a bare `String` answer; `effects().systemMessage(<grounding + honest-decline instructions>).mcpTools(RemoteMcpTools.fromService("akka-agentic-scala3")).userMessage(question).thenReply()` with `.onFailure(...)` degrading a failed turn to a clean message + a `logger.warn` of the real cause (FR-010, cap-6 robustness pattern). Scaladoc the interop finding (R1: `.mcpTools` is Scala-clean) and the self-call topology chosen in T003.
- [x] T006 Implement `McpClientEndpoint` in `src/main/scala/com/gwgs/akkaagentic/mcpclient/api/McpClientEndpoint.scala`: `@HttpEndpoint`, `@Acl(allow = INTERNET)`, constructor-injected `ComponentClient`; `POST /grounded-ask` with idiomatic `AskRequest(question: Option[String])` / `AskReply(answer: String)` DTOs; validate via `AskQuestion.validate(Option(question))` → `Left` ⇒ `HttpResponses.badRequest(message)` (no agent call), `Right` ⇒ `componentClient.forAgent().inSession(UUID).dynamicCall[String, String]("mcp-client-agent").invoke(question)` → `AskReply`. (Uses `dynamicCall` per §2 — agent client is Scala-clean.)
- [x] T007 `mvn compile` green — the mixed build compiles cap-10; descriptor FQCNs resolve.

**Checkpoint**: Endpoint + agent live; the loop is wired; test strategy fixed by S1/S2.

---

## Phase 3: User Story 1 — Grounded Q&A via the MCP tool (Priority: P1) 🎯 MVP

**Goal**: A caller's in-corpus question yields a grounded answer produced by the model calling the
remote `retrieve` tool; an out-of-corpus question yields an honest decline.

**Independent Test**: POST an in-corpus question → grounded answer that could only come from the
retrieved passages (SC-001, SC-003); POST an out-of-corpus question → decline, not fabrication (SC-002).

- [x] T008 [US1] In `src/test/scala/com/gwgs/akkaagentic/mcpclient/api/McpClientEndpointIntegrationTest.scala`, add the grounded-answer test: `TestModelProvider` scripts a `retrieve` tool call for an in-corpus question (per the T004 pattern/fallback) and replies with an answer built from the real `ToolResult`; assert `POST /grounded-ask` returns `200` with that grounded `answer` (SC-001, SC-003). If S2's fallback is live-only, assert the wiring here and mark the closed loop for the live smoke (T014).
- [x] T009 [US1] Add the honest-decline test: `TestModelProvider` scripts a decline (retrieve returns weak/no-cover passages, model replies "I don't know…"); assert `200` with a non-fabricated decline in `answer` (SC-002).

**Checkpoint**: MVP — grounded Q&A through the MCP tool works and is testable.

---

## Phase 4: User Story 2 — Validation-first rejection (Priority: P2)

**Goal**: Blank/malformed input is rejected before any model or tool call.

**Independent Test**: Blank question → `400`, no calls; malformed body → `400`; unknown extra field
tolerated (SC-004).

- [x] T010 [US2] Add to the integration test: blank/whitespace `question` → `400` with `question must not be blank` and no agent/tool call (assert via a `TestModelProvider` that would fail if invoked, or absence of interaction); malformed JSON body → `400` (SDK auto); a valid body with an unknown extra field → accepted (FR-007). (SC-004.)

**Checkpoint**: Validation contract matches every other capability.

---

## Phase 5: User Story 3 — One corpus, reached two ways (Priority: P3)

**Goal**: Confirm the agent's remote `retrieve` surfaces the same passages as a direct call to the
shared store.

**Independent Test**: For a question, the passages via the agent's MCP tool == the top passages a
direct `KnowledgeStore.retrieve` returns (SC-005).

- [x] T011 [US3] Add the parity test: assert the `ToolResult` the mock received in T008 (the real retrieved passages) equals `KnowledgeStore.fromCorpus().retrieve(question, 3)` (source labels + order), proving one corpus reached two ways (SC-005). If S2 was live-only, assert the direct retrieval here and cross-reference the live smoke for the tool path.

**Checkpoint**: The closed loop is shown to hit the shared store, not a second corpus.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T012 `mvn verify` — full suite green (180 pre-cap-10 + new cap-10 tests); confirm no live model contacted.
- [x] T013 Live smoke (Ollama `qwen3:8b` or Gemini): `exec:java`, then `POST /grounded-ask` with an in-corpus question (real model calls `retrieve`, grounded answer), an out-of-corpus question (decline), and a blank question (`400`). Record the closed-loop result (SC-003) and the interop verdict evidence (SC-007).
- [x] T014 [P] Docs: add README **§12** (cap-10 interop: `.mcpTools(...)` Scala-clean; the "wall is a client property" through-line extended to outbound calls; the S1/S2 outcomes) + a cap-10 usage/curl section + a project-layout entry; update `ROADMAP.md` (cap-10 done) and `FINDINGS.md` (SC-007 finding).
- [x] T015 [P] Update memory: roadmap `akka-agentic-exploration-roadmap.md` (cap-10 merged/status) and a new finding note for the `.mcpTools` Scala-clean result; link `[[akka-scala-componentclient-dynamiccall]]` and cap-9's MCP-server finding.
- [x] T016 Run `/akka.analyze` for cross-artifact consistency; fix any flagged drift before PR.

---

## Dependencies & Execution Order

- **Setup (Phase 1)**: no deps.
- **Foundational (Phase 2)**: T003/T004 (spikes) → T005 (agent) → T006 (endpoint) → T007 (compile). T003 and T004 are independent of each other but both precede T005/T006 (they decide topology + test shape). **Blocks all user stories.**
- **US1 (Phase 3)**, **US2 (Phase 4)**, **US3 (Phase 5)**: all depend only on Phase 2; independently testable. US3's offline assertion reuses the `ToolResult` captured in T008 (soft dependency; else stands alone via direct retrieval).
- **Polish (Phase 6)**: after the desired stories; T012 gates the PR; T013 proves the live loop; T014/T015 [P] docs/memory; T016 last.

### Parallel Opportunities

- T014 and T015 (docs, memory) are `[P]` — different files.
- The three user-story test slices (T008/T009, T010, T011) touch the same test file, so run **sequentially** within that file (not `[P]`), but they are logically independent stories.

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → 2. Phase 2 Foundational (spikes decide the wiring) → 3. Phase 3 US1 →
**STOP and VALIDATE** the grounded-Q&A loop → demo.

### Incremental Delivery

Foundation → US1 (MVP: grounded Q&A) → US2 (validation) → US3 (parity) → Polish (verify + live +
docs). Each story adds value without breaking the prior.

---

## Notes

- `[P]` = different files, no deps. `[Story]` maps a task to its user story.
- The two spikes (T003/T004) are **decision tasks**: their outcome edits research.md and may add one
  additive ACL line to cap-9 — the only anticipated touch outside the cap-10 package.
- Per CLAUDE.md: STOP for user approval between major steps; commit only when directed; keep `.env`
  git-ignored (verify `git check-ignore .env` before any `git add`).
- Two-mapper boundary (§3): agent payload bare `String`; HTTP DTOs idiomatic `Option`-typed.
