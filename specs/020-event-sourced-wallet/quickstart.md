# Quickstart: Event-sourced wallet (capability 18 / A3)

The repo's first **event-sourced entity**: a `Wallet` whose balance is a fold over `Opened`/`Deposited`/
`Withdrawn`/`Closed` events, not a stored number. It exists to answer whether a Scala 3 sum type can be the
event hierarchy the SDK handles — answer (measured): **no**, so the events are a Java `sealed interface`
while the entity and state stay Scala. See [research.md](research.md).

**No model, no API key, no network.** This capability calls no LLM at all (like capabilities 11, 15, 16).
Booting the service also starts the other capabilities' agents, but they connect to Ollama lazily — only
when *those* endpoints are called — so nothing here needs `ollama serve` running.

## Run it

```shell
mvn compile exec:java
# the service listens on http://localhost:9000
```

## Open, deposit, withdraw, read the folded balance

```shell
curl -i -X POST http://localhost:9000/wallets/alice/open \
  -H "Content-Type: application/json" -d '{"startingBalance":100}'
# 201 Created

curl -i -X POST http://localhost:9000/wallets/alice/deposit \
  -H "Content-Type: application/json" -d '{"amount":50}'
# 200 OK

curl -i -X POST http://localhost:9000/wallets/alice/withdraw \
  -H "Content-Type: application/json" -d '{"amount":30}'
# 200 OK

curl -s http://localhost:9000/wallets/alice
# {"balance":120,"open":true}      — 100 + 50 - 30, reached by folding three events
```

A wallet with no events reads as its empty state, never a `404`:

```shell
curl -s http://localhost:9000/wallets/nobody
# {"balance":0,"open":false}
```

## Forbidden operations are 400 — and record no event

```shell
# overdraw
curl -i -X POST http://localhost:9000/wallets/alice/withdraw \
  -H "Content-Type: application/json" -d '{"amount":999}'
# 400 Bad Request — insufficient funds

# non-positive amount
curl -i -X POST http://localhost:9000/wallets/alice/deposit \
  -H "Content-Type: application/json" -d '{"amount":0}'
# 400 Bad Request — amount must be positive

# close, then any operation on a closed wallet
curl -i -X POST http://localhost:9000/wallets/alice/close          # 200 OK
curl -i -X POST http://localhost:9000/wallets/alice/deposit \
  -H "Content-Type: application/json" -d '{"amount":5}'
# 400 Bad Request — wallet not open

curl -s http://localhost:9000/wallets/alice
# {"balance":120,"open":false}     — the balance never moved on any rejected call
```

## Where things live

| Concern | File | Language | Why |
|---|---|---|---|
| State + rules | `wallet/domain/Wallet.scala` | Scala | round-trips as a case class for snapshots (Q-C); rules SDK-free (FR-009) |
| Events | `wallet/domain/WalletEvent.java` | **Java** | the SDK's startup validation requires a JVM `sealed interface` (Q-A/Q-B) |
| Entity | `wallet/application/WalletEntity.scala` | Scala | folds events into state |
| Endpoint | `wallet/api/WalletEndpoint.java` | **Java** | the entity client is method-reference-only (Q-D) |

## Gotchas measured here (research Q-A–Q-F)

- A Scala 3 `enum` event **serializes but cannot be read back** ("Cannot reflectively create enum objects").
- A Scala 3 `sealed trait` **round-trips through the mapper but fails startup validation** (`isSealed == false`).
- The unit `EventSourcedTestKit.method(...)` needs a **Java method reference**; a Scala lambda throws
  `ClassCastException` in `Reflect.getReturnType`. Unit-test the domain in pure Scala instead.
- `@TypeName` and `snapshot-every` are both **service-global**, not per-entity.
- `ReadOnlyEffect[Long]` reads back as `Object` for a Java method-ref caller — the endpoint uses `get()`
  returning the full state to avoid it.

## Tests

```shell
mvn test -Dtest=WalletTest                      # pure domain rules, no runtime (fast)
mvn clean verify                                # full gate incl. the wallet integration tests
```
