# Quickstart: Greeting Agent Service Baseline

How to build, test, and run the Scala 3 greeting service.

## Prerequisites

- JDK 21
- Maven 3.9+
- A model provider API key for running against a live model (e.g. `ANTHROPIC_API_KEY` or
  `OPENAI_API_KEY`). **Not required for tests** — tests use `TestModelProvider`.

## Project layout (after implementation)

```text
src/main/scala/com/example/domain/Greeting.scala        # GreetingRequest / GreetingResponse (+ validation)
src/main/scala/com/example/application/GreetingAgent.scala
src/main/scala/com/example/api/GreetingEndpoint.scala
src/main/resources/application.conf                      # default model-provider config
src/test/scala/com/example/application/GreetingAgentTest.scala
src/test/scala/com/example/api/GreetingEndpointIntegrationTest.scala
```

## Build

```bash
mvn compile
```

First build also compiles Scala via `scala-maven-plugin` (added to `pom.xml`).

## Test (offline, no live model)

```bash
mvn test      # unit: GreetingAgentTest
mvn verify    # + integration: GreetingEndpointIntegrationTest
```

Both suites register a `TestModelProvider` for `GreetingAgent`, so no API key or network is
needed and results are deterministic (SC-004).

## Run locally

```bash
export ANTHROPIC_API_KEY=sk-...        # or OPENAI_API_KEY, matching application.conf
mvn compile exec:java
```

Service listens on `http://localhost:9000`.

## Try it

```bash
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"hello there"}'
# 200 OK
# {"greeting":"Hello Ada! ..."}

curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"","text":"hi"}'
# 400 Bad Request
# user must not be blank
```

## What "done" looks like

- `mvn verify` passes from a clean checkout with no code edits (SC-003).
- Valid request → personalized greeting naming the user (SC-001, US1).
- Missing/blank `user` or `text` → `400` with no greeting (SC-002, US2).
- Two different `text` values for the same `user` → two different greetings (SC-005).
