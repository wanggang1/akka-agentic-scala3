# Data Model: Session memory (multi-turn chat)

Capability 4 owns almost no data — the conversation history is the SDK's, keyed by id. The only types
this feature defines are a domain validator and the endpoint's wire DTOs. Deliberately, there is **no
Java-shaped component payload** (research R4): the agent's command and reply are a bare `String`.

## Domain (`com.gwgs.akkaagentic.chat.domain`)

### `ChatMessage`

A validated, non-blank user message for one turn. Mirrors cap-3's `HelpQuestion` — parse-don't-validate:
the constructor is reached only for a proven-present, trimmed message.

| Field | Type | Notes |
|-------|------|-------|
| `text` | `String` | non-blank, trimmed |

**Validation** (idiomatic Scala, no Akka imports — Constitution II domain independence):

```scala
object ChatMessage:
  def validate(raw: Option[String]): Either[String, ChatMessage] =
    raw.map(_.trim).filterNot(_.isBlank).map(ChatMessage.apply)
       .toRight("message must not be blank")
```

- Absent (`None`) or blank/whitespace-only → `Left("message must not be blank")` → HTTP 400 (FR-006).
- Present, non-blank → `Right(ChatMessage(trimmed))`; downstream code uses `.text` without re-checking.

There is **no** `Conversation` or `Turn` domain type: those are concepts the *platform* owns inside
`SessionMemoryEntity` (research R1). Modeling them here would duplicate SDK state (Constitution I & IV).

## Application (`com.gwgs.akkaagentic.chat.application`)

### `ChatAgent` (request-based `Agent`) — payload types

The command handler takes and returns plain `String`; no wrapper records (AGENTS.md: a single `String`
parameter needs no command record). No structured result type crosses the internal mapper (research R4).

| Aspect | Value |
|--------|-------|
| Command | `message: String` |
| Reply | `Effect[String]` |
| Memory | `MemoryProvider.limitedWindow()` (explicit; research R5) |
| Session id | supplied by the caller via `.inSession(sessionId)` (not minted) |

## API (`com.gwgs.akkaagentic.chat.api`) — wire DTOs

Idiomatic Scala (feature 003): annotation-free case classes, `Option` fields, `@JsonIgnoreProperties`
to tolerate unknown props (FR-008). These cross the **public** mapper (Bootstrap's Scala module).

### `ChatRequest` (inbound body)

| Field | Type | Notes |
|-------|------|-------|
| `message` | `Option[String]` | absent/null → `None` → validation 400 (never a 500) |

```scala
@JsonIgnoreProperties(ignoreUnknown = true)
final case class ChatRequest(message: Option[String])
```

### `ChatReply` (outbound body)

| Field | Type | Notes |
|-------|------|-------|
| `sessionId` | `String` | echoes the path id so the caller can correlate/continue (FR-010) |
| `reply` | `String` | the assistant's answer for this turn |

```scala
final case class ChatReply(sessionId: String, reply: String)
```

The `sessionId` is composed by the endpoint from the path — it is *not* part of the agent payload,
keeping that payload a trivial `String`.

## Platform-owned state (not defined here, for reference)

| Concept | Owner | Notes |
|---------|-------|-------|
| Conversation history | SDK `SessionMemoryEntity` (event-sourced) | keyed by `sessionId`; stores each user + AI message; replayed as context; bounded by the limited-window size policy (FR-002/003/009) |
| Isolation | SDK | distinct `sessionId` ⇒ distinct memory entity ⇒ no cross-talk (FR-004) |

## Validation & rule summary

| Rule | Where | Requirement |
|------|-------|-------------|
| Message non-blank / present | `ChatMessage.validate` (domain) | FR-006 |
| Malformed body → 400 | SDK Jackson at endpoint boundary | FR-007 |
| Unknown props tolerated | `@JsonIgnoreProperties(ignoreUnknown = true)` on `ChatRequest` | FR-008 |
| History bounded | SDK limited-window policy | FR-009 |
| Reply carries id | `ChatReply.sessionId` from path | FR-010 |
| Capabilities 1–3 untouched | new package only; additive descriptor | FR-011 |
