# Quickstart: to-do activity (capability 16)

The activity feed has no model of its own, but its **source** is capability 6's assistant, which does — so
seeing it live needs Ollama (see README "Run locally"). The tests need neither.

```shell
ollama serve &                                   # if not already running
mvn compile exec:java
```

## Make something happen, then read what was noticed

```shell
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"add a to-do to buy milk"}'
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"mark to-do 1 as done"}'

curl -s http://localhost:9000/todo-activity/alice
# {"since":"…","entries":[
#   {"sequence":1,"username":"alice","kind":"baseline","open":0,"completed":0,…},   (first sighting)
#   {"sequence":2,"username":"alice","kind":"added","itemId":1,"description":"buy milk",…},
#   {"sequence":3,"username":"alice","kind":"completed","itemId":1,"description":"buy milk",…}]}
```

Nothing called the feed. The assistant wrote capability 6's to-do list; the consumer noticed.

Whether the first entry is `baseline` or `added` depends on whether the consumer saw an earlier state for
alice — both are correct; the feed never invents history it did not observe.

## See what was published

With no broker locally, each published message is written to the service log (`eventing.support = logging`):

```shell
# in the service log:  … todo-activity … {"username":"alice","changes":[{"kind":"completed",…}],…}
```

## Set-asides

```shell
curl -s http://localhost:9000/todo-activity/set-aside
# {"since":"…","setAsides":[]}        — nothing has failed
```

Failure can't be provoked from outside without breaking capability 6, so it is exercised by the tests, on
the real projection path.

## Two things to know

**A restart empties the feed, and the missed history does not come back.** The consumer resumes from its
stored position (measured: 0 events replayed after a restart), so changes it delivered before the restart
are not delivered again. `since` on every response says which window you are looking at.

**One bad message would otherwise stall everyone.** The platform redelivers a failing message without limit
(measured: 0, 0.3, 0.8, 1.7, 3.4, 7.0, 14.0, 27.6 s … and climbing) and holds every other user's changes
behind it. The consumer gives up after `feed.max-attempts` (default 3), records a set-aside, and moves on.

## Tests

```shell
mvn clean verify
```

No model and no broker. Delivery and publishing use the TestKit's mocked channels; failure is tested by
writing to the real entity, because the mocked channel **drops** failing messages instead of redelivering
them (research Q-F) — a failure test there would pass for the wrong reason.
