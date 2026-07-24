# API Contract: Human-in-the-loop approval gate (`ApprovalEndpoint`)

Four routes on one endpoint, secured `@Acl(principal = INTERNET)`. **Start → poll → decide → poll.** All
state is derived from the three-task chain keyed by a single `caseId` (see data-model.md); there is no
Entity. Bodies are idiomatic Scala DTOs (optional-typed, unknown properties ignored).

Base package: `com.gwgs.akkaagentic.approvals.api` · Endpoint: `ApprovalEndpoint`

---

## 1. `POST /approvals` — submit a question, start the gated case

Validates the question first (FR-010). On success, mints `caseId`, creates the draft → gate → publish
task chain, assigns the draft and publish agents (the gate stays unassigned), and returns immediately.

**Request** (`SubmitRequest`)
```json
{ "question": "A customer asks: how do I get a refund?" }
```

**Responses**

| Status | When | Body |
|---|---|---|
| `202 Accepted` | valid question | `{ "caseId": "…" }` + `Location: /approvals/{caseId}` |
| `400 Bad Request` | blank/absent `question` | `question must not be blank` — no case started, no model call |
| `400 Bad Request` | malformed JSON | SDK-generated — no case started |

Unknown JSON properties are tolerated.

```bash
curl -i -X POST http://localhost:9000/approvals \
  -H "Content-Type: application/json" \
  -d '{"question":"How do I get a refund?"}'
# 202 Accepted
# Location: /approvals/2f1c...
# {"caseId":"2f1c..."}
```

---

## 2. `GET /approvals/{caseId}` — poll the case state

Reads the three task snapshots and reports one state (see the data-model state machine). Distinguishes
every lifecycle state; only an unknown handle is `404` (FR-002, FR-003, US3).

**Responses**

| Status | State (`state` field) | Extra fields |
|---|---|---|
| `200 OK` | `drafting` | — |
| `200 OK` | `awaiting-approval` | `draft` (the candidate reply) |
| `200 OK` | `publishing` | `draft` |
| `200 OK` | `published` | `reply` (the final reply) |
| `200 OK` | `rejected` | `note` (reviewer's note, if any) |
| `200 OK` | `draft-failed` | `note` (failure reason) |
| `200 OK` | `publish-failed` | `draft`, `note` (failure reason) — approved but publishing failed; beyond the spec's minimum, reported rather than polling `publishing` forever |
| `404 Not Found` | unknown `caseId` | `approval case not found` — never a fabricated draft/reply |

```bash
# while drafting
curl -s http://localhost:9000/approvals/2f1c...
# {"state":"drafting"}

# once the draft is ready (gate open)
# {"state":"awaiting-approval","draft":"You can request a refund within 30 days…"}

# after approval + publish
# {"state":"published","reply":"You can request a refund within 30 days…"}
```

Empty optional fields are omitted from the JSON (idiomatic Scala).

---

## 3. `POST /approvals/{caseId}/approve` — human approves the draft

Allowed only when the gate is open (`draft COMPLETED` ∧ `approval PENDING`). Assigns the gate to the
reviewer label and completes it with an `ApprovalDecision`, releasing the publish task (FR-004, FR-005,
FR-007).

**Request** (`DecisionRequest`, all fields optional)
```json
{ "note": "Looks good." }
```

**Responses**

| Status | When | Body |
|---|---|---|
| `200 OK` | gate open | `approved` |
| `404 Not Found` | unknown `caseId` | `approval case not found` |
| `409 Conflict` | still drafting / draft failed | `case is not awaiting approval` |
| `409 Conflict` | already approved or rejected | `case has already been decided` — safe no-op, no second publish (FR-009) |

```bash
curl -i -X POST http://localhost:9000/approvals/2f1c.../approve \
  -H "Content-Type: application/json" -d '{"note":"Looks good."}'
# 200 OK — Approved.  Then GET polls to state "published".
```

---

## 4. `POST /approvals/{caseId}/reject` — human rejects the draft

Allowed only when the gate is open. Assigns the gate and **fails** it with the note as the reason;
failing the gate auto-cancels the publish task, so no reply is ever published (FR-006). The note is
retained and shown by `GET` in the `rejected` state.

**Request** (`DecisionRequest`, `note` optional but recommended)
```json
{ "note": "Tone is too casual; please revise." }
```

**Responses**

| Status | When | Body |
|---|---|---|
| `200 OK` | gate open | `rejected` |
| `404 Not Found` | unknown `caseId` | `approval case not found` |
| `409 Conflict` | still drafting / draft failed | `case is not awaiting approval` |
| `409 Conflict` | already approved or rejected | `case has already been decided` — safe no-op |

```bash
curl -i -X POST http://localhost:9000/approvals/2f1c.../reject \
  -H "Content-Type: application/json" -d '{"note":"Too casual."}'
# 200 OK — Rejected.  Then GET polls to state "rejected" with the note; no reply is ever published.
```

---

## Status-code summary

| Code | Meaning in this contract |
|---|---|
| `202` | case accepted; draft is being produced asynchronously |
| `200` | GET state read; or a decision applied |
| `400` | invalid input (blank question / malformed body) — nothing started |
| `404` | unknown `caseId` (GET or decision) — never fabricated |
| `409` | decision refused: gate not open, or already decided (safe no-op) |

## Contract test checklist (all offline, mocked models; FR-014)

- **C1** POST valid → 202 + `Location` + `caseId`; GET immediately → `drafting` (no draft, no reply).
- **C2** After draft mock completes → GET `awaiting-approval` with `draft`; **no `reply` present** (SC-003).
- **C3** approve → 200; poll → `published` with `reply`; the reply corresponds to the approved draft (SC-002).
- **C4** reject with note → 200; poll → `rejected` carrying the note; **`reply` never present, ever** (SC-003, FR-006).
- **C5** GET unknown `caseId` → 404; approve/reject unknown → 404 (FR-008).
- **C6** approve/reject **before** draft ready → 409 (not awaiting); state unchanged (edge case).
- **C7** second decision after a gate is decided → 409; terminal outcome unchanged, no second publish (FR-009).
- **C8** blank question → 400, no case; malformed body → 400, no case (FR-010, SC-006).
- **C9** draft agent abandons (mock `failTask`) → GET `draft-failed`, distinct from `awaiting-approval`; gate never opens (edge case).
- **C10** the two terminal states (`published`, `rejected`) are distinguishable from each other and from in-progress (US3).
