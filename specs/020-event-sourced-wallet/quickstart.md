# Quickstart: Event-sourced wallet

**Feature**: `020-event-sourced-wallet` (capability 18 / A3)

## What this is

The repo's first **event-sourced entity**: a `Wallet` whose balance is a fold over `Opened`/`Deposited`/
`Withdrawn`/`Closed` events, not a stored number. It exists to answer whether a Scala 3 sum type can be the
event hierarchy the SDK handles — answer (measured): **no**, so the events are a Java `sealed interface`
while the entity and state stay Scala. See [research.md](research.md).

## Run the probe measurements (Phase 0 evidence)

```bash
# the serializer round-trip (Q-B/Q-C) and the unit-testkit wall (Q-E) — fast, pure logic
mvn test -Dtest='WalletProbeTest,WalletSerializationProbeTest,WalletJavaUnitProbeTest'

# boot the whole service + drive the entity over the real journal (startup validation, Q-D, snapshot)
mvn verify -Dit.test=WalletProbeIntegrationTest -Dtest='!*' -DfailIfNoTests=false
```

## Exercise the wallet over HTTP (after implementation)

```bash
# open with a starting balance
curl -i -XPOST localhost:9000/wallets/alice/open -H 'Content-Type: application/json' -d '{"startingBalance":100}'

# deposit and withdraw
curl -i -XPOST localhost:9000/wallets/alice/deposit  -H 'Content-Type: application/json' -d '{"amount":50}'
curl -i -XPOST localhost:9000/wallets/alice/withdraw -H 'Content-Type: application/json' -d '{"amount":30}'

# read the folded balance
curl -s localhost:9000/wallets/alice            # {"balance":120,"open":true}

# forbidden: overdraw / operate on a closed wallet → 400, and NO event is recorded
curl -i -XPOST localhost:9000/wallets/alice/withdraw -H 'Content-Type: application/json' -d '{"amount":999}'
curl -i -XPOST localhost:9000/wallets/alice/close
curl -i -XPOST localhost:9000/wallets/alice/deposit  -H 'Content-Type: application/json' -d '{"amount":5}'  # 400
```

## Where things live

| Concern | File | Language | Why |
|---|---|---|---|
| State + rules | `wallet/domain/Wallet.scala` | Scala | round-trips as a case class (Q-C); rules SDK-free (FR-009) |
| Events | `wallet/domain/WalletEvent.java` | **Java** | the SDK requires a JVM `sealed interface` (Q-A/Q-B) |
| Entity | `wallet/application/WalletEntity.scala` | Scala | compiles and runs; folds events into state |
| Endpoint | `wallet/api/WalletEndpoint.java` | **Java** | the entity client is method-reference-only (Q-D) |

## Gotchas measured here

- A Scala `enum` event **serializes but cannot be read back** ("Cannot reflectively create enum objects").
- A Scala `sealed trait` **round-trips but fails startup validation** (`isSealed == false`).
- The unit `EventSourcedTestKit.method(...)` needs a **Java method reference**; a Scala lambda throws
  `ClassCastException` in `Reflect.getReturnType`. Unit-test the domain in pure Scala instead.
- `ReadOnlyEffect[Long]` reads back as `Object` for a Java method-ref caller — cast via `Number`.
