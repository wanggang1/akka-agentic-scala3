# Data Model: Autonomous help-desk Agent

Phase 1 output. Entities, the task lifecycle, and the wire/payload types, with the serialization shape
(idiomatic vs Java-shaped) called out per the two-mapper boundary (research R3).

## Entities

### HelpQuestion (inbound, validated)

The caller's free-text question, validated before any task starts.

| Field      | Type     | Notes                                              |
|------------|----------|----------------------------------------------------|
| `question` | `String` | Required, non-blank after trim.                    |

- **Validation** (`parse`, not re-validate): `HelpQuestion.validate(raw: Option[String]):
  Either[String, HelpQuestion]` — `None`/blank → `Left("question must not be blank")`; otherwise
  `Right(HelpQuestion(trimmed))`. Pure domain, no Akka deps. Mirrors cap-1's parse-don't-validate
  (`GreetingRequest.validate`).

### HelpAnswer (task result — **Java-shaped**)

The typed result the agent produces when it completes the task. This is a **component payload**
(delivered via the built-in `complete_task` tool and serialized by the SDK's internal mapper), so it is
**Java-shaped**: a Jackson-annotated Scala case class with non-optional fields — the cap-1
`GreetingAgent.Result` pattern (research R3).

| Field         | Type           | Notes                                                        |
|---------------|----------------|-------------------------------------------------------------|
| `answer`      | `String`       | The response text.                                          |
| `category`    | `String`       | Short classification of the question (agent-chosen).        |
| `citedTopics` | `List[String]` | Knowledge-base topics the agent consulted; possibly empty.  |
| `confidence`  | `Int`          | Agent's self-reported confidence, 0–100.                    |

- Annotated `@JsonCreator` / `@JsonProperty` so the internal (annotation-free) mapper can both derive
  the `complete_task` JSON schema and construct the instance from the model's tool arguments.
- `citedTopics` is a `java.util.List`-friendly shape at the boundary; kept simple (empty list, never
  null) so downstream code never null-checks.

### KnowledgeBaseEntry / KnowledgeBase (domain, pure)

The canned reference the agent may consult via its `@FunctionTool`.

| Field     | Type     | Notes                              |
|-----------|----------|------------------------------------|
| `topic`   | `String` | The lookup key (case-insensitive). |
| `summary` | `String` | The canned reference text.         |

- `KnowledgeBase.lookup(topic: String): Option[KnowledgeBaseEntry]` — pure, case-insensitive, `None`
  for an unknown topic. A small in-memory `Map` of a handful of entries (e.g. `password-reset`,
  `billing`, `account`, `shipping`). Pure Scala, no Akka deps.
- The agent's tool returns a **String** ("no entry for …" on a miss) so the model always gets a usable
  result and never errors on an unknown topic (spec edge case).

## Wire types (HTTP boundary — **idiomatic Scala**)

Defined as inner types of `HelpDeskEndpoint`. These are HTTP request/response bodies, which the
Scala-aware `ObjectMapper` (feature 003 `Bootstrap`) covers, so they may be **idiomatic** (annotation-
free, `Option` fields).

- `AskRequest(question: Option[String])` — the POST body; `Option` so an absent field deserializes to
  `None` and is rejected by validation (not a 500). Tolerates unknown properties.
- `StartAccepted(taskId: String)` — the `202` body: the handle to poll.
- `HelpReply(answer: String, category: String, citedTopics: List[String], confidence: Int)` — the `200`
  body; the endpoint's own type, mapped from `HelpAnswer` via a `toApi` conversion (never exposes the
  task result type directly — API isolation).

## Task lifecycle

One `HelpDeskTasks.ANSWER` task per request. Status is owned by the runtime; the endpoint only reads a
snapshot.

```text
            runSingleTask(ANSWER.instructions(question))
   (start) ─────────────────────────────────────────────▶ PENDING/ASSIGNED/IN_PROGRESS
                                                                     │
                        model iterates: optionally lookupPolicy(…) × n
                                                                     │
                         ┌───────────────────────────────┬──────────┴───────────┐
                         ▼                                ▼                      ▼
                    complete_task(HelpAnswer)        fail_task(reason)     iteration limit hit
                         │                                │                      │
                         ▼                                ▼                      ▼
                     COMPLETED                          FAILED              FAILED (bounded)
```

- **maxIterationsPerTask**: small (e.g. 5) — bounds the loop for cost and guarantees termination
  (spec edge case "runaway iteration").
- **Snapshot → HTTP** (read by `GET /help/{taskId}`): see the contract for the exact mapping
  (`COMPLETED`→`200`, `FAILED`→`422`, otherwise/unknown→`404`).

## Serialization boundary (summary)

| Type                         | Crosses…                              | Shape                         |
|------------------------------|---------------------------------------|-------------------------------|
| `AskRequest` / `HelpReply`   | HTTP endpoint body (public mapper)    | **Idiomatic** Scala (`Option`)|
| `HelpAnswer` (task result)   | `complete_task` tool (internal mapper)| **Java-shaped** (Jackson ann.)|
| `HelpQuestion`, `KnowledgeBase*` | never serialized (in-process)     | Plain Scala                   |

## Component registration (descriptor)

Add to `akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf` (research R2):

- `autonomous-agent = ["com.gwgs.akkaagentic.assistant.application.HelpDeskAgent"]` (new key)
- append `"com.gwgs.akkaagentic.assistant.api.HelpDeskEndpoint"` to `http-endpoint`
- `HelpDeskTasks` and `HelpAnswer` are **not** components — no entry.
