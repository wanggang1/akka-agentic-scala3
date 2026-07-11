# Quickstart: Autonomous help-desk Agent (capability 3)

Capability 3 is a self-contained **Scala** module (`com.gwgs.akkaagentic.assistant.*`) added alongside
the untouched Scala cap-1 and Java cap-2. It is a single **Autonomous Agent** that answers a question
through a model-driven loop (optionally consulting a knowledge-base tool) and completes a **typed
task**, exposed async (start → poll).

## Build & test (offline, no API key)

```shell
mvn verify
```

cap-3 is Scala, compiled by `scala-maven-plugin` alongside cap-1; cap-2's Java is compiled by
`maven-compiler-plugin` (`-proc:none`). The build config is unchanged — no `pom.xml` edit is needed.
Tests use `TestModelProvider` (+ `AutonomousAgentTools`), so no model, key, or network is required.
Capabilities 1 and 2 suites must stay green unchanged (SC-006).

## Run locally

```shell
cp .env.example .env          # set GOOGLE_AI_GEMINI_API_KEY
set -a && source .env && set +a && mvn compile exec:java
```

All three capabilities are served: `POST /greet` (cap-1), `/greetings` (cap-2), and `/help` (cap-3).

## Exercise the async flow

**1. Ask a question** — returns immediately with a handle:

```shell
curl -i -X POST http://localhost:9000/help \
  -H "Content-Type: application/json" \
  -d '{"question":"How do I reset my password?"}'
# 202 Accepted
# Location: /help/<taskId>
# {"taskId":"<taskId>"}
```

**2. Poll for the result** — 404 while the agent iterates, 200 when the task completes:

```shell
curl -i http://localhost:9000/help/<taskId>
# 404 Not Found        (still running)
# ...retry...
# 200 OK
# {"answer":"To reset your password, …","category":"account",
#  "citedTopics":["password-reset"],"confidence":90}
```

`citedTopics` is populated when the model chose to consult the knowledge base; it may be empty when the
model answered directly — the decision is the model's, not a fixed step.

**A task the agent can't answer → 422 (distinct from not-ready):**

```shell
curl -i http://localhost:9000/help/<taskId>
# 422 Unprocessable Content
# <failure reason>
```

**Invalid input — rejected up front, no task started:**

```shell
curl -i -X POST http://localhost:9000/help \
  -H "Content-Type: application/json" -d '{"question":""}'
# 400 Bad Request
```

## What to look for

- **Model-driven loop, not fixed steps** — unlike cap-2's Workflow, no code sequences the work. The
  agent decides whether/how often to call `lookupPolicy` before it calls the built-in `complete_task`
  tool. Watch the iteration in the logs / Akka console.
- **The task is the durable record** — the answer is queryable by its `taskId` at any time via
  `forTask(taskId).get(ANSWER)`; there is no wrapping Workflow.
- **cap-1 & cap-2 untouched** — `POST /greet` and `/greetings` behave exactly as before.

## Gotchas (from research)

- **Scala, on purpose — no method-ref wall.** The Autonomous Agent API is `Class`/`Task`/annotation-
  based with **no** `SerializedLambda` step wiring (research R1) — the inverse of cap-2's Workflow wall
  — so cap-3 is idiomatic Scala. This is capability 3's headline finding.
- **Descriptor key is `autonomous-agent`.** Add `HelpDeskAgent` to the hand-maintained descriptor under
  the new `autonomous-agent` key (confirmed from the annotation processor), and the endpoint under
  `http-endpoint` (research R2). The `Tasks` holder and `HelpAnswer` are not components.
- **Task result stays Java-shaped.** `HelpAnswer` is a Jackson-annotated Scala case class because the
  `complete_task` result crosses the SDK's *internal* mapper (the two-mapper finding, research R3); the
  HTTP DTOs stay idiomatic. If schema-gen misbehaves for the Scala case class, fall back to a Java record
  for that one type.
- **Gemini is fine here.** The typed result is delivered by function calling (`complete_task`), not a
  JSON response mime type, so the Gemini tools-vs-JSON constraint from cap-1/cap-2 does not apply
  (research R4).
