# Quickstart: session compaction (capability 17)

Compaction has no surface of its own to call — it happens to a conversation that gets long. So seeing it
live means holding a long conversation, which means a model: Ollama drives capability 6's assistant (see
README "Run locally"). The tests need neither a model nor a long conversation.

The threshold is lowered here so a handful of turns crosses it. At the shipped default (128 KiB) you would
be typing for a while.

```shell
ollama serve &                                        # if not already running
COMPACTION_MAX_BYTES=4096 mvn compile exec:java
```

## Make a conversation long enough to be compacted

```shell
for i in 1 2 3 4 5 6; do
  curl -s -X POST http://localhost:9000/request/alice \
    -H "Content-Type: application/json" \
    -d "{\"message\":\"turn $i — tell me something about akka, at length\"}" > /dev/null
done

curl -s http://localhost:9000/compaction/alice
# {"sessionId":"alice","compactions":1,"lastBytesBefore":4271,"lastBytesAfter":206,
#  "lastMessagesReplaced":10,"lastOutcome":"compacted","lastAt":"…"}
```

`lastBytesBefore` is what crossed the threshold; `lastBytesAfter` is read **back from the entity**, not
predicted — a stale write is accepted silently by the platform, so the number you see is the one that
actually landed (research R-4).

## The conversation still knows what it established

```shell
curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"my name is Ada"}'

# ...several more turns, enough to cross the threshold again...

curl -s -X POST http://localhost:9000/request/alice \
  -H "Content-Type: application/json" -d '{"message":"what is my name?"}'
# ...still answers "Ada" — the summary carried it across the compaction
```

**This is the one thing no offline test in this project can show.** Capabilities 4 and 6 both measured that
a mocked model receives only the current turn, so the mock cannot demonstrate the model *using* a summary.
Offline tests assert the summary **contains** what was established; that it is then *used* is verified here,
live, and nowhere else.

## It applies to every session, not just the assistant's

Capability 4's chat and capability 14's streamed chat store history in the same place, so they get the
bound too — without either capability being modified:

```shell
for i in 1 2 3 4 5 6; do
  curl -s -X POST http://localhost:9000/chat/c-123 \
    -H "Content-Type: application/json" -d "{\"message\":\"turn $i, at length please\"}" > /dev/null
done

curl -s http://localhost:9000/compaction
# {"since":"…","sessions":[{"sessionId":"alice",...},{"sessionId":"c-123",...}]}
```

## Turn it off

`false` reproduces capability 6's behaviour exactly — useful for reproducing a problem, or for a deployment
that would rather pay for full history:

```shell
COMPACTION_ENABLED=false mvn compile exec:java
# conversations grow until the SDK's own 510 KiB bound evicts the oldest turns instead
```

## Two things to know

**A restart empties the record, not the histories.** The compacted histories live in the runtime's entity
and survive; the ledger behind `GET /compaction` is in-process and does not. That is why every response
carries `since`, and why an unknown session is a `404` that deliberately does **not** distinguish "never
needed compacting" from "lost to a restart" — this capability cannot tell.

**What compaction is for turned out not to be what the spec first said.** Phase 0 measured that history is
**already** bounded, at 510 KiB, and that the SDK's eviction is **turn-aligned and safe** — it sweeps until
the head of the window is a user message, so it can never split a tool-call pair the way `readLast(N)` did.
So this is not a bug fix. It is a cost and continuity feature: 510 KiB is ~100k+ tokens re-sent every turn,
and eviction discards the oldest turns leaving nothing behind. Compaction shrinks history **while keeping
what it meant**. See research S-1/S-2.

## Tests

```shell
mvn verify -Dit.test='Compaction*' -Dtest='!*'
```

No live model, and no long conversation — the threshold is overridden per test. The summariser is mocked
with `TestModelProvider`. Recall *through* the model is the one live-only claim, above.
