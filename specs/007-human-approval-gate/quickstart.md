# Quickstart: Human-in-the-loop approval gate (capability 5)

A durable human-approval gate on the Akka Autonomous Agent **external-input** pattern — draft → human
gate → publish — authored entirely in Scala (tests included). Start → poll → decide → poll.

## Build & test (offline, no key)

```shell
mvn compile          # compiles Scala 3 (mixed build already configured — no pom change for cap-5)
mvn verify           # runs the whole suite; caps 1–4 unchanged, cap-5 added — all offline
```

The cap-5 tests register a `TestModelProvider` per agent (`DraftAgent`, `PublishAgent`) — **no API key
or network** required. The human approve/reject decisions are issued from the test through the endpoint,
so the tests are pure Scala (the headline contrast with cap-4's forced Java entity test).

## Run locally (live model)

Needs a Google AI Gemini key (as with the other capabilities):

```shell
cp .env.example .env          # then edit .env and set GOOGLE_AI_GEMINI_API_KEY
set -a && source .env && set +a && mvn compile exec:java
# service on http://localhost:9000
```

## The gated flow — approve path

```shell
# 1. Submit a question — 202 + a case handle; the draft is being produced
curl -i -X POST http://localhost:9000/approvals \
  -H "Content-Type: application/json" \
  -d '{"question":"How do I get a refund?"}'
# 202 Accepted
# Location: /approvals/2f1c...
# {"caseId":"2f1c..."}

# 2. Poll — "drafting" until the agent finishes, then "awaiting-approval" with the draft
curl -s http://localhost:9000/approvals/2f1c...
# {"state":"drafting"}
# ...then...
# {"state":"awaiting-approval","draft":"You can request a refund within 30 days…"}

# 3. Approve — releases the publish step
curl -i -X POST http://localhost:9000/approvals/2f1c.../approve \
  -H "Content-Type: application/json" -d '{"note":"Looks good."}'
# 200 OK — Approved.

# 4. Poll — "published" with the final reply
curl -s http://localhost:9000/approvals/2f1c...
# {"state":"published","reply":"You can request a refund within 30 days…"}
```

## The gated flow — reject path

```shell
# After polling to "awaiting-approval" on a fresh case:
curl -i -X POST http://localhost:9000/approvals/<id>/reject \
  -H "Content-Type: application/json" -d '{"note":"Tone is too casual; please revise."}'
# 200 OK — Rejected.

curl -s http://localhost:9000/approvals/<id>
# {"state":"rejected","note":"Tone is too casual; please revise."}
# No reply is ever published for a rejected case.
```

## Validation & decision integrity

```shell
# blank question — 400, no case started
curl -i -X POST http://localhost:9000/approvals \
  -H "Content-Type: application/json" -d '{"question":"  "}'
# 400 Bad Request — question must not be blank

# unknown handle — 404
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9000/approvals/does-not-exist   # 404

# decision before the draft is ready — 409 (not awaiting approval)
# second decision after a gate is decided — 409 (already decided; no second publish)
```

## Live smoke test (the end-to-end human gate — FR-014 / SC-008)

Automated tests mock the model, so the real "agent drafts → human waits → agent publishes" loop is
proven manually once, against Gemini:

1. `set -a && source .env && set +a && mvn compile exec:java`
2. `POST /approvals {"question":"How do I get a refund?"}` → capture `caseId`.
3. Poll `GET /approvals/{caseId}` until `awaiting-approval`; confirm a real drafted reply is shown and
   **no `reply` field is present**.
4. `POST /approvals/{caseId}/approve` → poll to `published`; confirm a final reply appears **only after
   approval**.
5. Repeat on a new case, but `POST …/reject` → poll to `rejected` with the note; confirm **no `reply` is
   ever produced**.

> **Durability (optional, FR-011):** run with the on-disk store
> (`-Dakka.javasdk.dev-mode.persistence.enabled=true`), drive a case to `awaiting-approval`, restart the
> process, then approve — the gate is still pending after restart and the case still publishes. The
> durable record is the **task chain**, not any Entity of ours.

## What to notice (the learning goal)

- The human decision (`approve`/`reject`) is a plain `TaskClient` call (`assign` + `complete`/`fail`) —
  **no method reference**, so it is idiomatic Scala. A Workflow `resume(...)` gate would have forced Java.
- There is **no Entity** and **no Workflow**: the case is reconstructed from one `caseId` by deriving the
  three task ids, and the tasks are the durable record. Avoiding an Entity is what keeps the whole
  capability — tests included — in Scala.
- Capabilities 1–4 are untouched; all five are served together (the descriptor lists every component).
