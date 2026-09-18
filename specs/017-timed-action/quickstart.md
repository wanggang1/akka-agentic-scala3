# Quickstart: scheduled reminders (capability 15)

No model, no API key, no network. This capability calls no LLM at all — the second such capability in
the project, after capability 11.

```shell
mvn compile exec:java
```

## Schedule, watch it fire

```shell
curl -i -X POST http://localhost:9000/reminders \
  -H "Content-Type: application/json" \
  -d '{"note":"stand up and stretch","delaySeconds":5}'
# 201 Created · Location: /reminders/8f3c…
# {"reminderId":"8f3c…","note":"stand up and stretch","delaySeconds":5,"state":"pending"}

curl -s http://localhost:9000/reminders/8f3c…
# {"reminderId":"8f3c…","note":"stand up and stretch","state":"pending"}

sleep 6
curl -s http://localhost:9000/reminders/8f3c…
# {"reminderId":"8f3c…","note":"stand up and stretch","state":"fired","firedAt":"…"}
```

Measured overhead is ~100 ms (research Q-E), so a 5 s reminder fires at about 5.1 s.

## Cancel before it fires

```shell
ID=$(curl -s -X POST http://localhost:9000/reminders -H "Content-Type: application/json" \
      -d '{"note":"cancel me","delaySeconds":30}' | sed 's/.*"reminderId":"\([^"]*\)".*/\1/')

curl -i -X DELETE http://localhost:9000/reminders/$ID
# 200 OK — {"reminderId":"…","state":"cancelled"}

sleep 31
curl -s http://localhost:9000/reminders/$ID
# still {"state":"cancelled"} — it never fired
```

Cancelling twice is a `409`, not a second success:

```shell
curl -i -X DELETE http://localhost:9000/reminders/$ID
# 409 Conflict — {"reminderId":"…","state":"cancelled"}
```

## Validation runs first

```shell
curl -i -X POST http://localhost:9000/reminders \
  -H "Content-Type: application/json" -d '{"note":"  ","delaySeconds":60}'
# 400 Bad Request — note must not be blank        (nothing scheduled)

curl -i -X POST http://localhost:9000/reminders \
  -H "Content-Type: application/json" -d '{"note":"ok","delaySeconds":0}'
# 400 Bad Request — delaySeconds must be between 1 and 86400
```

## Two things to know before relying on this

**A restart loses pending reminders.** Measured: a 60 s timer scheduled, the service killed 10 s later
with the on-disk store enabled, restarted 8 s after that — and the timer **never fired**, watched for
100 s past its due time. So `GET` returns `404` for an id from before a restart, which is the accurate
answer rather than a stale `pending`. This is local dev mode; a deployed service has a real datastore
and may behave differently — untested here, and not claimed either way. See
[research.md](research.md) Q-C.

**Retries are bounded on purpose.** The three-argument `createSingleTimer` was measured **still
retrying at 30 seconds** with widening gaps (2, 3, 3, 3, 4, 4 attempts at 5 s intervals). This
capability always uses the four-argument overload with an explicit `maxRetries`, and a test pins that
the three-argument form appears nowhere in its production code.

## Tests

```shell
mvn clean verify
```

Fully offline and deterministic. Timer firing is observed by waiting 1–1.5 s (the HTTP floor) — `TimedActionTestkit`
invokes the action *directly*, which proves what the action does but never that a timer fired, so the
timer itself has to be waited for. Cancellation costs one wait past a delay to prove a negative.

Measured on the final `mvn clean verify` (2026-09-18): the whole suite takes **3:42**, and capability 15's
tests **~29 s** of it — unit tests effectively zero, integration tests 3.6 s (scheduling), 5.8 s
(cancellation), 3.7 s (probe) and **15.8 s** (the retry bound). About 18.5 s of that is deliberate
waiting; the largest single piece is the retry test's settle, three times the ~3 s backoff it observes,
because proving that work *stopped* means outlasting the retry that did not come.

## Where the interop line falls

**Scheduling is Java, cancelling is Scala** — one component family, both sides of the method-reference
wall, split by operation. `ReminderSchedulingEndpoint.java` exists only because
`TimedActionClient.method(...)` needs a Java method reference and there is no `dynamicCall` and no
`deferred()` on the escape hatch. The `TimedAction` itself, the domain rule, the store, the read and
cancel endpoint, and every test are Scala. A quarantine test pins the Java count at one.
