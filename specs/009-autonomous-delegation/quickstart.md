# Quickstart: Autonomous-agent delegation (activity-suggestion coordinator)

**Feature**: 009-autonomous-delegation · **Date**: 2026-08-07

## Run locally (Ollama, no key)

```shell
ollama pull qwen3:8b            # once (a strong tool-calling model matters — delegation is function calling)
mvn compile exec:java           # http://localhost:9000
```

## Start a suggestion → poll for the synthesized result

```shell
# 1. Start — 202 + a task handle; the coordinator is delegating to specialists
curl -i -X POST http://localhost:9000/activities \
  -H "Content-Type: application/json" \
  -d '{"location":"Boston","preferences":"outdoorsy, with kids"}'
# 202 Accepted
# Location: /activities/2f1c...
# {"taskId":"2f1c..."}

# 2. Poll — 404 while coordinating, 200 with the synthesized suggestion once COMPLETED
curl -s http://localhost:9000/activities/2f1c...
# 404 Not Found        (while the coordinator delegates + synthesizes)
# ...then...
# 200 OK
# {"suggestion":"With clear skies ~20°C, try Boston Common playground then a harbor walk.",
#  "weatherConsidered":"Clear, ~20°C",
#  "consultedSpecialists":["weather-specialist","activity-specialist"]}
```

`consultedSpecialists` shows which delegates the coordinator's model chose — it can differ between a
conditions-only ask and an activity-seeking ask (that is the dynamic-delegation point, SC-002).

## Validation & poll semantics

```shell
curl -i -X POST http://localhost:9000/activities \
  -H "Content-Type: application/json" -d '{"location":"  "}'
# 400 Bad Request — location must not be blank   (no task started)

curl -s http://localhost:9000/activities/does-not-exist
# 404 Not Found
```

## Verify (offline, deterministic)

```shell
mvn verify     # TestModelProvider per agent; no key, no network
```

## First implementation checkpoints (from research.md)

1. **Delegation adaptation smoke** (research D1): a minimal live coordinator → one request-based specialist,
   to confirm the request-based-worker adaptation before building the rest. Fallback: AutonomousAgent workers.
2. **Offline delegation mocking** (research D9): read `autonomous-agents/testing.html.md`; confirm the
   coordinator's delegation + specialists can be scripted with `TestModelProvider`. Fallback: prove
   delegation live, assert wiring offline.
