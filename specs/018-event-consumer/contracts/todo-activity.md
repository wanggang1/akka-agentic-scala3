# Contract: to-do activity (capability 16)

A **Scala** consumer reads capability 6's to-do lists and records what changed. Three read routes, one topic.
All production code is Scala; nothing here needed Java (research Q-A/Q-C).

Every response carries **`since`** — the moment this process began recording. The feed is in-process: a
restart empties it, and the consumer, which **resumes** from its stored position rather than replaying
(research Q-E), will not resend what it already delivered. `since` makes that window visible instead of
silent.

## `GET /todo-activity` — the whole feed, oldest first

```json
{"since":"2026-09-19T11:07:24Z",
 "entries":[
   {"sequence":1,"username":"alice","kind":"baseline","open":0,"completed":0,"recordedAt":"…"},
   {"sequence":2,"username":"alice","kind":"added","itemId":1,"description":"buy milk","recordedAt":"…"},
   {"sequence":3,"username":"alice","kind":"completed","itemId":1,"description":"buy milk","recordedAt":"…"}]}
```

`kind` ∈ `baseline` · `added` · `completed` · `reopened` · `removed` · `list-deleted`. Optional fields are
omitted when empty. An empty feed is `200` with `"entries":[]` — "nothing has happened" is an answer.

**What the feed is not.** It is an *activity* record, not an audit log: capability 6 is a key-value source,
which delivers the latest state and may fold changes together (so an item added and removed between two
deliveries never appears), and a newly started consumer does not see history — hence `baseline` on first
sighting rather than a false run of `added`.

## `GET /todo-activity/{username}` — one user's entries

Same shape, filtered. An unknown user is `200` with no entries, not `404`: the feed only knows who has
done something since `since`.

## `GET /todo-activity/set-aside` — changes that could not be processed

```json
{"since":"…","setAsides":[{"username":"alice","attempts":3,"reason":"…","recordedAt":"…"}]}
```

A delivery that fails `feed.max-attempts` times is recorded here and **skipped**, so it cannot stall every
other user (measured: without the bound, one failing message is redelivered without limit and holds back
everyone else — research Q-D). Set-asides are never counted as activity and never published.

## Topic `todo-activity` — what is published

One message per delivery **that produced changes** (none for a duplicate, none for a set-aside):

```json
{"username":"alice","changes":[{"kind":"added","itemId":1,"description":"buy milk"}],"recordedAt":"…"}
```

Metadata `ce-subject` = username, so a broker keeps each user's messages in order. The payload is an
idiomatic Scala type — measured to serialize through the Scala-aware mapper (research Q-C).

**Locally** there is no broker: `akka.javasdk.dev-mode.eventing.support = "logging"` writes each message to
the service log. Without that setting the **whole service refuses to start** (`AK-00406`, research Q-C).
A deployed service needs a broker configured in the Akka project; that is untested here.
