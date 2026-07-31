# Phase 1 Data Model: Agent-to-agent delegation (personal assistants)

Derived from the spec's Key Entities and Requirements, and from research.md. Layering follows the project
rule: idiomatic Scala **domain** validation and **API** DTOs; **Java-shaped** component payloads (entity
state + agent wire type) because they cross the SDK's internal mapper (R4 / feature-003). The language of
each type is dictated by the interop wall (R1/R2), not by layer.

> **Reconciliation note**: three domain files were drafted on-branch before this doc and match the model
> below — `src/main/java/.../a2a/domain/Todo.java`, `.../TodoList.java`, and
> `src/main/scala/.../a2a/domain/AssistantRequest.scala`. This document describes what shipped; the
> `/akka.implement` step should reconcile (not duplicate) them.

---

## 1. Domain layer — to-do state (Java, `com.gwgs.akkaagentic.a2a.domain`)

Java records because they are (or are part of) the `TodoEntity` state, serialized by the SDK's **internal**
Jackson mapper — component state must be Java-shaped (R4). The domain object owns the to-do logic so the
entity's command handlers stay thin (AGENTS.md).

### `Todo`

| Field | Type | Notes |
|---|---|---|
| `id` | `int` | unique **within one user** (assistant), assigned by `TodoList` |
| `description` | `String` | the task text |
| `completed` | `boolean` | done flag |

```java
public record Todo(int id, String description, boolean completed) {}
```

### `TodoList` (entity state + logic)

Immutable, copy-on-write. The `TodoEntity` KeyValueEntity state.

| Member | Signature | Behavior |
|---|---|---|
| `empty()` | `static TodoList` | an assistant with no to-dos |
| `todos()` | `List<Todo>` | unmodifiable, defensively copied |
| `nextId` | `int` | monotonic **high-water** mark (persisted field), starts at `1` — the id `add` will assign; only ever grows |
| `add(description)` | `TodoList` | append an incomplete `Todo` with id `nextId()` |
| `find(id)` | `Optional<Todo>` | the item, if present |
| `delete(id)` | `TodoList` | copy without `id` (unchanged copy if absent) |
| `setCompleted(id, completed)` | `TodoList` | copy with that id's flag set (unchanged copy if absent) |

**Rules**: ids never reused within a list — `nextId` is a monotonic high-water mark that only grows, so
deleting the highest item does **not** let a later `add` reclaim its id; every mutator returns a **new**
`TodoList` (immutability); `delete`/`setCompleted` on an unknown id are **no-ops** (the *entity* reports
found/not-found to the tool by consulting `find` first — FR-003).

---

## 2. Domain layer — request validation (Scala, `com.gwgs.akkaagentic.a2a.domain`)

Idiomatic Scala, parse-don't-validate. Mirrors cap-3 `HelpQuestion` / cap-5 `ApprovalQuestion`.

### `AssistantRequest`

| Field | Type | Notes |
|---|---|---|
| `username` | `String` | guaranteed non-blank, trimmed |
| `message` | `String` | guaranteed non-blank, trimmed |

```scala
final case class AssistantRequest(username: String, message: String)

object AssistantRequest:
  def validate(rawUsername: String, rawMessage: Option[String]): Either[String, AssistantRequest] =
    for
      username <- Option(rawUsername).map(_.trim).filterNot(_.isBlank).toRight("username must not be blank")
      message  <- rawMessage.map(_.trim).filterNot(_.isBlank).toRight("message must not be blank")
    yield AssistantRequest(username, message)
```

**Rules**: `null`/absent and blank both fail → `Left(...)` → HTTP 400 with **no** model call (FR-007). The
`username` is validated before use because it doubles as the session id (chat history) and the `TodoEntity`
id (R6) — nothing is addressed until it is proven present.

---

## 3. Application layer — the agent wire type (Java-shaped Scala)

Crosses the internal mapper, so Jackson-annotated with a nullable-tolerant creator, like cap-1
`GreetingAgent.Request`. **Not** on the public API surface (R4).

### `PersonalAssistantAgent.Request`

| Field | Type | Notes |
|---|---|---|
| `username` | `String` | whose assistant this is (session id + to-do key + system-prompt name) |
| `message` | `String` | the user (or delegating assistant's) message |
| `delegated` | `Boolean` | **default `false`**; set `true` **only** by `ForwardTool` — the one-hop guard |

The reply is a **bare `String`** (the assistant's answer) — no result wire type needed (like cap-4).

**State/flow**: the command handler validates nothing itself (the endpoint already parsed via
`AssistantRequest`); it builds the system message `"You are {username}, a concise and helpful assistant."`,
sets `.memory(limitedWindow().readLast(N))`, and offers tools by `delegated` (R4):

```
delegated == false → tools: [TodoTools, ForwardTool]     (top-level request may delegate)
delegated == true  → tools: [TodoTools]                  (a delegate cannot delegate again)
```

---

## 4. API layer — HTTP DTOs (idiomatic Scala, `com.gwgs.akkaagentic.a2a.api`)

Go through the Scala-aware mapper, so `Option`-typed and annotation-free (feature-003).

### Request body — `POST /request/{username}`

| Field | Type | Notes |
|---|---|---|
| `message` | `Option[String]` | validated via `AssistantRequest.validate(username, body.message)` |

`username` is a **path** parameter, not a body field. `delegated` is **absent** (internal only — FR-005).

### Response body — `200 OK`

| Field | Type | Notes |
|---|---|---|
| `username` | `String` | echoes the path (whose assistant replied) |
| `reply` | `String` | the assistant's answer (or a delegated reply, relayed verbatim + attribution) |

---

## 5. The two-mapper boundary (why the split, at a glance)

| Type | Layer | Mapper | Shape |
|---|---|---|---|
| HTTP request/response DTOs | api (Scala) | `JsonSupport` (Scala-aware) | idiomatic `Option`, annotation-free |
| `PersonalAssistantAgent.Request` | application (Scala) | internal | **Java-shaped** (Jackson-annotated) |
| `Todo`, `TodoList` (entity state) | domain (Java) | internal | **Java-shaped** (Java records) |
| `AssistantRequest` | domain (Scala) | none (never serialized) | idiomatic Scala |

## 6. Tools exposed to the model (not data types, but the model's contract)

| Tool | Object (lang) | Signature (intent) | Effect |
|---|---|---|---|
| `listTodos` | `TodoTools` (Java) | `() → String` (rendered list) | read `TodoEntity` |
| `addTodo` | `TodoTools` (Java) | `(description) → int id` | `TodoEntity.add` |
| `deleteTodo` | `TodoTools` (Java) | `(id) → boolean found` | `TodoEntity.delete` |
| `setCompleted` | `TodoTools` (Java) | `(id, completed) → boolean found` | `TodoEntity.setCompleted` |
| `askAssistant` | `ForwardTool` (Scala) | `(username, question) → String reply` | delegate via agent `dynamicCall`, `delegated=true` |

`TodoTools` is keyed by the **caller's** username (the agent passes it in when constructing the tool for
that session); `ForwardTool` targets the **named** username. Both tool objects are constructed with the
injected `ComponentClient`; neither is a component (absent from the descriptor).
