# Quickstart: To-do Read Model (cap-11)

**Feature**: `013-views-read-model` | **Date**: 2026-08-28

## Verify offline (no model, no key, no network)

```shell
mvn verify
```

**This capability is the first in the project whose tests need no model at all** — not even a mocked
one. No `TestModelProvider` appears in any of its three test classes; the projection and both queries
are deterministic.

## Try it locally

To-dos are still written only through capability 6's assistant, so seed some through it, then read
them back through the new read model.

```shell
mvn compile exec:java     # local Ollama by default; see README
```

```shell
# 1. Seed via the cap-6 assistant (this is the ONLY write path)
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"add a to-do to buy milk"}'
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"add a to-do to call the dentist"}'
curl -s -X POST http://localhost:9000/request/bob \
  -H "Content-Type: application/json" -d '{"message":"add a to-do to prepare slides"}'

# 2. Read one user's standing through the read model
curl -s http://localhost:9000/todo-summaries/by-user/alice
# {"username":"alice","total":2,"open":2,"completed":0}

# 3. The cross-user question no other surface can answer
curl -s http://localhost:9000/todo-summaries/with-open-work
# {"summaries":[{"username":"alice",...},{"username":"bob",...}]}

# 4. Complete something, then watch the counts move
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"mark to-do 1 as done"}'
curl -s http://localhost:9000/todo-summaries/by-user/alice
# {"username":"alice","total":2,"open":1,"completed":1}
```

> **If step 4 still shows the old counts, retry once.** The read model is a projection and is
> **eventually consistent** — it converges a moment after the write. That is the design, not a bug.

```shell
# Not-found vs. bad-request are different answers
curl -i -s http://localhost:9000/todo-summaries/by-user/nobody     # 404 Not Found
curl -i -s "http://localhost:9000/todo-summaries/by-user/%20%20"   # 400 Bad Request
```

## Survives a restart

The view is rebuilt from the source entity state, so the read model is durable in the same sense as
the rest of the project. To see it across a **local** restart, use the on-disk store (as with
cap-3 / cap-5 / cap-7):

```shell
mvn compile exec:java -Dakka.javasdk.dev-mode.persistence.enabled=true
```

## What to look at

| | |
|---|---|
| The View (**Scala**) | `src/main/scala/com/gwgs/akkaagentic/todos/application/TodoSummaryView.scala` — note the updater lives in the **companion object** (research R2); an inner class would not survive the SDK's reflection |
| The derivation (**Scala**, pure) | `src/main/scala/com/gwgs/akkaagentic/todos/domain/TodoSummary.scala` |
| The endpoint (**Java**) | `src/main/java/com/gwgs/akkaagentic/todos/api/TodoSummaryEndpoint.java` — Java because `ViewClient` is method-reference-only (research R1) |
| The descriptor | `src/main/resources/META-INF/akka-javasdk-components_…conf` — new `view` key (research R4) |
| The finding | README "Scala interop notes" §13 and `FINDINGS.md` |
