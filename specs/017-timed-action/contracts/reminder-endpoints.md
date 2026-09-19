# Contract: reminder endpoints (capability 15)

Three operations. They are split across **two endpoint classes in two languages**, which is a
consequence of the measurement, not a style choice — see [research.md](../research.md) Q-A/Q-B.

| Operation | Path | Class | Language | Why |
|---|---|---|---|---|
| schedule | `POST /reminders` | `ReminderSchedulingEndpoint` | **Java** | holds the `TimedActionClient` method reference |
| read | `GET /reminders/{id}` | `ReminderEndpoint` | Scala | no method reference |
| cancel | `DELETE /reminders/{id}` | `ReminderEndpoint` | Scala | `TimerScheduler.delete(String)` is string-keyed |

Both carry `@Acl(allow = @Acl.Matcher(principal = INTERNET))`, as every other capability's endpoint does.

---

## `POST /reminders` — schedule

```json
{"note": "stand up and stretch", "delaySeconds": 60}
```

| Response | When | Body |
|---|---|---|
| `201 Created` + `Location: /reminders/{id}` | scheduled | `{"reminderId":"…","note":"…","delaySeconds":60,"state":"pending"}` |
| `400 Bad Request` | validation failed | the domain's message, as plain text |

Validation runs **before** anything is scheduled — the project-wide validation-first contract. A `400`
leaves no timer and no store entry.

```shell
curl -i -X POST http://localhost:9000/reminders \
  -H "Content-Type: application/json" -d '{"note":"stand up","delaySeconds":60}'
# 201 Created · Location: /reminders/8f3c…
curl -i -X POST http://localhost:9000/reminders \
  -H "Content-Type: application/json" -d '{"note":"  ","delaySeconds":60}'
# 400 Bad Request — note must not be blank
```

## `GET /reminders/{id}` — read

| Response | When |
|---|---|
| `200 OK` | the reminder is known |
| `404 Not Found` | unknown id — **or a known id from before a restart** (see below) |

```json
{"reminderId":"8f3c…","note":"stand up","state":"fired","firedAt":"2026-09-18T09:52:17.214Z"}
```

`state` is one of `pending`, `fired`, `cancelled`, `failed`. `firedAt` is present only on `fired`,
`cancelledAt` only on `cancelled`, `failure` only on `failed` — empty optionals are omitted, as
elsewhere in this project.

> **A restart empties this.** Reminder state lives in the service process, because a pending **timer
> does not survive a restart either** (measured — research Q-C). So after a restart a previously known
> id returns `404`, and that is the *accurate* answer: the reminder is gone, and reporting it as still
> `pending` would promise a firing that can no longer happen.

> **Known limit — nothing is evicted.** A finished reminder (`fired`, `cancelled`, `failed`) stays readable
> until the process restarts; memory grows with every reminder ever scheduled. A retention window for
> finished reminders was considered in PR review and deferred. If added, `GET` on a finished reminder
> would become `404` once the window passes.

## `DELETE /reminders/{id}` — cancel

Three outcomes, kept distinct (FR-006). Answering "cancelled" to a request that cancelled nothing is
the specific error this contract exists to prevent.

| Response | Meaning | Body |
|---|---|---|
| `200 OK` | it was pending; it is now cancelled and will not fire | `{"reminderId":"…","state":"cancelled"}` |
| `409 Conflict` | already terminal — nothing to cancel | `{"reminderId":"…","state":"fired"}` (or `failed`/`cancelled`) |
| `404 Not Found` | unknown id | plain text |

```shell
curl -i -X DELETE http://localhost:9000/reminders/8f3c…
# 200 OK — {"reminderId":"8f3c…","state":"cancelled"}
curl -i -X DELETE http://localhost:9000/reminders/8f3c…   # again
# 409 Conflict — {"reminderId":"8f3c…","state":"cancelled"}
```

A cancel that returns `200` is a **guarantee the action never runs**, not a best effort: measured in
Q-B, a cancelled timer fired 0 times when waited past its delay.

---

## What fires

`ReminderAction.fire(reminderId)` — a Scala `TimedAction` — marks the reminder `fired` in the store.
Scheduled with the **four-argument** `createSingleTimer(name, delay, maxRetries, deferred)`; the
three-argument form is banned in this capability, because it was measured still retrying at 30 seconds
(research Q-D). On exhausted retries the reminder is `failed`, which a caller reads through `GET`.

## Error semantics summary

| Situation | Status |
|---|---|
| blank/absent note, or `delaySeconds` out of range | `400`, nothing scheduled |
| malformed JSON | `400` (SDK-supplied) |
| `GET`/`DELETE` unknown id | `404` |
| `DELETE` on a terminal reminder | `409` with the actual state |
| the action throws past `maxRetries` | not an HTTP error — `GET` reports `failed` |
