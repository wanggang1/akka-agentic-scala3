# Quickstart: Greeting Agent Service Baseline

How to build, test, and run the Scala 3 greeting service.

## Prerequisites

- JDK 21
- Maven 3.9+
- A Google AI Gemini API key for running against a live model. The service reads it from the
  `GOOGLE_AI_GEMINI_API_KEY` environment variable (configured in `application.conf`).
  **Not required for tests** — tests use `TestModelProvider`.

### Configure the API key (`.env`)

Copy the template and add your key:

```bash
cp .env.example .env
# edit .env and set GOOGLE_AI_GEMINI_API_KEY=...
```

`.env` is git-ignored. The JVM does **not** read `.env` automatically — load it into the
environment before running (see [Run locally](#run-locally)).

## Project layout (after implementation)

```text
src/main/scala/com/gwgs/akkaagentic/domain/Greeting.scala        # GreetingRequest / GreetingResponse (+ validation)
src/main/scala/com/gwgs/akkaagentic/application/GreetingAgent.scala
src/main/scala/com/gwgs/akkaagentic/api/GreetingEndpoint.scala
src/main/resources/application.conf                      # default model-provider config
src/test/scala/com/gwgs/akkaagentic/application/GreetingAgentTest.scala
src/test/scala/com/gwgs/akkaagentic/api/GreetingEndpointIntegrationTest.scala
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

Load the `.env` into the environment, then start the service:

```bash
set -a && source .env && set +a && mvn compile exec:java
```

`set -a` exports everything `source .env` assigns so the child JVM inherits
`GOOGLE_AI_GEMINI_API_KEY`; `set +a` turns that off again. Service listens on
`http://localhost:9000`.

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
