# Quickstart: Agent-to-agent delegation (capability 6)

Personal assistants that remember, keep a to-do list, and can **delegate** to each other. Runs on the
default local Ollama model — no key.

## Prerequisites

```shell
ollama pull qwen3:8b            # once (reliable tool calling required)
mvn compile exec:java           # local Ollama; service on http://localhost:9000
```

For durability across a **local** restart (to see history/to-dos survive), enable the on-disk store:

```shell
mvn compile exec:java -Dakka.javasdk.dev-mode.persistence.enabled=true
```

## 1. Manage your own to-do list (natural language)

```shell
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"add a to-do to buy milk"}'
# {"username":"alice","reply":"Added \"buy milk\" as item 1."}

curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"add: call the dentist"}'
# {"username":"alice","reply":"Added \"call the dentist\" as item 2."}

curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"mark item 1 done"}'
# {"username":"alice","reply":"Marked item 1 completed."}

curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"what is on my list?"}'
# {"username":"alice","reply":"1. buy milk (done)\n2. call the dentist (open)"}
```

## 2. Delegate to another user's assistant

The effect lands under the **target** user; alice's reply relays bob's assistant's answer.

```shell
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" \
  -d '{"message":"ask bob'\''s assistant to add a to-do: prepare slides"}'
# {"username":"alice","reply":"Bob's assistant: Added \"prepare slides\" as item 1."}

curl -s -X POST http://localhost:9000/request/bob \
  -H "Content-Type: application/json" -d '{"message":"what is on my list?"}'
# {"username":"bob","reply":"1. prepare slides (open)"}   <- under bob, not alice
```

## 3. Memory & isolation (live)

```shell
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"my name is Alice"}'
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"what is my name?"}'
# recalls "Alice" (same username = same conversation)

curl -s -X POST http://localhost:9000/request/carol \
  -H "Content-Type: application/json" -d '{"message":"what is my name?"}'
# does NOT know "Alice" (different username = isolated)
```

## 4. Validation

```shell
curl -i -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"  "}'
# 400 Bad Request — message must not be blank   (no model call)
```

## Tests (offline, no model server)

```shell
mvn verify
```

- `TodoListTest` (Java) — add/delete/complete/nextId logic.
- `TodoEntityTest` (Java) — `KeyValueEntityTestKit` command handlers.
- `PersonalAssistantAgentTest` (Scala) — `TestModelProvider`: to-do tool-call paths; **loop guard**
  (a `delegated=true` request is offered no forward tool).
- `PersonalAssistantEndpointIntegrationTest` (Scala) — `httpClient`: A→B delegation lands under the
  target; `400` on blank/malformed.

> **Recall is proven live, not offline** — the mock model sees only the current turn (feature-006
> caveat), so multi-turn recall is verified by the step-3 live run, while retention/isolation and the
> delegation wiring are covered offline.
