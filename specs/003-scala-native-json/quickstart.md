# Quickstart: Scala-native JSON for wire types (feature 003)

Same toolchain as 001/002 (JDK 21, Maven 3.9+, Scala 3.3.4 via `scala-maven-plugin`). Offline
tests need no API key; the live smoke test needs a real Gemini key.

## What changes

- The service registers `DefaultScalaModule` on the SDK's Jackson `ObjectMapper` at startup, via a
  new `@Setup` `Bootstrap` class discovered through the descriptor entry
  `akka.javasdk.service-setup`.
- The greeting wire types (`GreetRequest`, `GreetReply`, `GreetingAgent.Request`/`Result`) become
  annotation-free Scala case classes; optional fields use `Option`; the `null → None` boundary
  conversions are deleted.
- The **`POST /greet` HTTP contract is unchanged** (see `contracts/greeting-api.md`).

## Build & test (offline)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn compile
mvn verify        # unit + integration, mocked model, no network
```

Expected: green from a clean checkout with no `GOOGLE_AI_GEMINI_API_KEY` (SC-004). Includes the new
round-trip test proving an annotation-free `Option`-bearing case class serializes both ways, and
the unchanged `HealthEndpoint` test (Java-shaped coexistence witness, SC-006).

## Live smoke test (required — provider-specific serialization; FR-008)

```bash
set -a && source .env && set +a && mvn compile exec:java   # needs a real Gemini key
```

```bash
# with timezone → 200, timeOfDay reflects the zone
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"How do I reset my password?","timezone":"America/New_York"}'
# 200 OK — {"greeting":"...","tone":"question","timeOfDay":"morning"}

# without timezone → 200, UTC fallback
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"hey there"}'
# 200 OK — {"greeting":"...","tone":"casual","timeOfDay":"..."}

# invalid input → 400, no model call
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"","text":"hi"}'
# 400 Bad Request — user must not be blank

# coexistence witness → still works
curl -i http://localhost:9000/health
# 200 OK — {"status":"ok"}
```

## What "done" looks like

- An annotation-free `Option`-bearing Scala case class round-trips end to end; offline suite green
  + live `POST /greet` returns `200` (SC-001).
- Converted types carry zero `@JsonCreator`/`@JsonProperty` and the endpoint/agent do zero
  `null → None` (`Option(...)`) conversions (SC-002).
- `POST /greet` behavior identical to 002 — `200` with three fields; `400` on invalid, no model
  call (SC-003); 100% of existing endpoint tests pass.
- `mvn clean verify` green offline from a clean checkout (SC-004).
- The `akka.javasdk.service-setup` discovery mechanism is documented in the README interop notes
  (SC-005).
- `HealthEndpoint.Health` remains Java-shaped and its test passes (SC-006).
