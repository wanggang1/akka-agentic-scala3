# Quickstart: Multi-agent greeting Workflow (capability 2)

Capability 2 is a self-contained **Java** module (`com.gwgs.akkaagentic.team.*`) added alongside the
untouched Scala capability 1. It orchestrates two agents through an Akka `Workflow`, exposed async
(start → poll).

## Build & test (offline, no API key)

```shell
mvn verify
```

Java (cap-2) and Scala (cap-1) compile independently — `scala-maven-plugin` compiles the Scala,
`maven-compiler-plugin` (with `-proc:none`) compiles the Java. Tests use `TestModelProvider`, so no
model, key, or network is required. Capability 1's suite must stay green unchanged (SC-006).

## Run locally

```shell
cp .env.example .env          # set GOOGLE_AI_GEMINI_API_KEY
set -a && source .env && set +a && mvn compile exec:java
```

Both capabilities are served: `POST /greet` (cap-1, synchronous) and `/greetings` (cap-2, async).

## Exercise the async flow

**1. Start a greeting** — returns immediately with a handle:

```shell
curl -i -X POST http://localhost:9000/greetings \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"How do I reset my password?","timezone":"America/New_York"}'
# 202 Accepted
# Location: /greetings/<id>
# {"id":"<id>"}
```

**2. Poll for the result** — 404 while running, 200 when the two agents have finished:

```shell
curl -i http://localhost:9000/greetings/<id>
# 404 Not Found        (still running)
# ...retry...
# 200 OK
# {"greeting":"Good evening, Ada — happy to help…","tone":"question","timeOfDay":"evening"}
```

The `tone` comes from the first agent (tone detection); the composing agent adapts the greeting to it
and weaves in the time of day from its `@FunctionTool`.

**Casual message, no timezone (UTC):**

```shell
curl -s -X POST http://localhost:9000/greetings \
  -H "Content-Type: application/json" -d '{"user":"Ada","text":"hey there"}'
# {"id":"<id>"}  → poll → {"greeting":"…","tone":"casual","timeOfDay":"…"}
```

**Invalid input — rejected up front, no workflow started:**

```shell
curl -i -X POST http://localhost:9000/greetings \
  -H "Content-Type: application/json" -d '{"user":"","text":"hi"}'
# 400 Bad Request
```

## What to look for

- **Two agent calls, one session** — the workflow runs `tone-agent` then `greeting-composer-agent`
  under a shared session id (the workflow id), visible in the logs / Akka console.
- **Durability** — if the tone step's model misbehaves, the workflow fails over to a neutral tone and
  still completes; if the composer's reply isn't JSON, its `onFailure` still returns a greeting. A
  `GET` never 500s once started.
- **cap-1 untouched** — `POST /greet` behaves exactly as before.

## Gotchas (from research)

- **Java, on purpose.** The whole module is Java because the Workflow API wires steps only via Java
  method references (`SerializedLambda`) that Scala can't produce, and `WorkflowClient` has no
  `dynamicCall` (research R1). This is capability 2's headline finding.
- **Descriptor by hand, processor off.** Java is compiled with `-proc:none` so the Akka annotation
  processor doesn't regenerate (and overwrite) the hand-maintained component descriptor (research R2).
  Every cap-2 component (`tone-agent`, `greeting-composer-agent`, `greeting-workflow`,
  `GreetingTeamEndpoint`) is added to that file by hand, including the new `workflow = [...]` key.
