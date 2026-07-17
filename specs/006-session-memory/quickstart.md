# Quickstart: Session memory / multi-turn chat (capability 4)

Capability 4 is a self-contained **Scala** module (`com.gwgs.akkaagentic.chat.*`) added alongside the
untouched cap-1 (Scala), cap-2 (Java), and cap-3 (Scala). It is a single request-based **Agent** that
holds a **multi-turn conversation**: the runtime's session memory, keyed by a caller-supplied
conversation id, remembers earlier turns and replays them as context. Synchronous (send → reply), no
polling.

## Build & test (offline, no API key)

```shell
mvn verify
```

cap-4 is Scala, compiled by `scala-maven-plugin` alongside cap-1/cap-3; cap-2's Java is compiled by
`maven-compiler-plugin` (`-proc:none`). The build config is unchanged — no `pom.xml` edit is needed.
Tests use `TestModelProvider`, so no model, key, or network is required. Capabilities 1–3 suites must
stay green unchanged (SC-006).

## Run locally

```shell
cp .env.example .env          # set GOOGLE_AI_GEMINI_API_KEY
set -a && source .env && set +a && mvn compile exec:java
```

All four capabilities are served: `POST /greet` (cap-1), `/greetings` (cap-2), `/help` (cap-3), and
`/chat/{sessionId}` (cap-4).

## Exercise the multi-turn flow

**1. State a fact** on a conversation id you choose:

```shell
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":"my name is Ada"}'
# 200 OK — {"sessionId":"c-123","reply":"Nice to meet you, Ada!"}
```

**2. Ask about it on the SAME id** — the reply recalls turn 1:

```shell
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" \
  -d '{"message":"what is my name?"}'
# 200 OK — {"sessionId":"c-123","reply":"Your name is Ada."}
```

**3. A different id knows nothing** (isolation):

```shell
curl -i -X POST http://localhost:9000/chat/c-999 \
  -H "Content-Type: application/json" \
  -d '{"message":"what is my name?"}'
# 200 OK — reply does not know "Ada"
```

**Invalid input — rejected up front, assistant never engaged:**

```shell
curl -i -X POST http://localhost:9000/chat/c-123 \
  -H "Content-Type: application/json" -d '{"message":"  "}'
# 400 Bad Request — message must not be blank
```

## What to look for

- **Context across requests** — turn 2 answers correctly only because turn 1 was retained and replayed.
  The two turns are independent HTTP requests; the id is the thread that links them.
- **Isolation by id** — `c-999` has no trace of `c-123`. Different id ⇒ different session-memory entity.
- **Memory is the platform's, not ours** — nothing in `ChatAgent` stores anything (no entity, no
  `persist`, no state). The SDK's `SessionMemoryEntity` records and replays automatically; durability is
  intrinsic (as with cap-3's task). We add **no descriptor entry** for that entity — the runtime owns it.
- **cap-1/2/3 untouched** — `/greet`, `/greetings`, `/help` behave exactly as before.

## Gotchas (from research)

- **Scala, zero new friction — the headline.** Memory is keyed by the same session-id string we already
  pass via `dynamicCall(...).inSession(id)`; the `MemoryProvider`/`MemoryFilter` API is builder-based
  (no method-ref wall); the payload is a bare `String` (no Java-shaped type needed at all). Capability 4
  is the cleanest Scala-on-Java-SDK capability in the project (research R1–R4).
- **Descriptor: two lines only.** `chat-agent` under `agent`, `ChatEndpoint` under `http-endpoint`. Do
  **not** add `SessionMemoryEntity` — it is an SDK-internal component the runtime registers (research R3).
- **Explicit memory for legibility.** `ChatAgent` sets `.memory(MemoryProvider.limitedWindow())` — the
  same as the default, spelled out because memory is the capability's subject (research R5). No
  `readLast(N)` (would risk evicting the fact under test).
- **Offline memory assertion — resolved in tests.** Whether `TestModelProvider.whenMessage` sees the
  replayed history (so a turn-2 predicate can match turn-1's fact) is confirmed empirically in
  `ChatAgentIntegrationTest`; a documented fallback covers the case where the mock sees only the latest
  message (research R6).
