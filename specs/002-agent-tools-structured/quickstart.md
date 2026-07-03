# Quickstart: Structured, context-aware greeting

How to build, test, and run the structured greeting (feature 002). Same toolchain as 001
(JDK 21, Maven 3.9+, Scala 3 via `scala-maven-plugin`); tests need no API key (mocked model).

## What changes vs. 001

- `POST /greet` returns a **structured object** `{greeting, tone, timeOfDay}` instead of just
  `{greeting}`.
- The request accepts an optional `timezone` (IANA id; blank/invalid → UTC).
- The agent gains a `@FunctionTool` that reports the current time-of-day; the time logic is a pure
  domain function (`TimeOfDay`) with its own unit tests.

## Build & test (offline)

```bash
mvn compile
mvn verify    # unit (domain + agent) + integration (endpoint), mocked model, no network
```

Expected: all green from a clean checkout with no `GOOGLE_AI_GEMINI_API_KEY` (SC-004).

## Run locally

```bash
set -a && source .env && set +a && mvn compile exec:java   # needs a real Gemini key for live runs
```

## Try it

```bash
# Structured success — note tone + timeOfDay in the response
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"How do I reset my password?","timezone":"America/New_York"}'
# 200 OK
# {"greeting":"Good evening, Ada — happy to help...","tone":"question","timeOfDay":"evening"}

# Timezone optional — falls back to UTC
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"hey there"}'
# 200 OK — {"greeting":"...","tone":"casual","timeOfDay":"..."}

# Invalid input still rejected, no model call
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"","text":"hi"}'
# 400 Bad Request — user must not be blank
```

## What "done" looks like

- Valid request → structured `{greeting, tone, timeOfDay}`, all fields present (SC-001, US1).
- `timeOfDay` reflects the request-time context; morning ≠ evening under controlled time (SC-002, US2).
- Invalid input → `400`, no model call, 100% (SC-003, US3).
- `mvn verify` passes offline from a clean checkout (SC-004).
- Greeting names the user and is consistent with tone and time-of-day (SC-005).
