# Data Model: Event-sourced wallet

**Feature**: `020-event-sourced-wallet` · derived from spec.md + research.md (measured)

## State — `Wallet` (Scala case class, `domain`)

| Field | Type | Meaning |
|---|---|---|
| `balance` | `Long` | current balance in whole minor units; never negative (FR-005) |
| `open` | `Boolean` | whether the wallet is open and accepting operations |

- Reconstructed by folding events (`applyEvent`), never stored as a bare value (FR-004).
- `emptyState()` = `Wallet(0, open = false)` — a wallet with no events is closed with zero balance.
- Round-trips through the SDK's internal mapper for snapshots with **no Jackson annotations**
  (research Q-C). Stays Scala — the sealed requirement is on the event type only.

### Pure rules (on `Wallet`, SDK-free — FR-009)

Each returns either the next state or a typed rejection; the entity turns a rejection into
`effects().error(...)` and never persists on rejection (FR-005–FR-007, US2).

| Rule | Precondition | Result |
|---|---|---|
| `open(start)` | not already open; `start >= 0` | `Opened(start)` → `Wallet(start, open=true)` |
| `deposit(amt)` | open; `amt > 0` | `Deposited(amt, balance+amt)` |
| `withdraw(amt)` | open; `amt > 0`; `amt <= balance` | `Withdrawn(amt, balance-amt)` |
| `close()` | open | `Closed(balance)` → `open=false` |

Parse-don't-validate: the domain exposes the decision (e.g. `Either[String, WalletEvent]`) so the entity
never re-checks. Exact shape chosen in implementation; the rules above are the contract.

## Events — `WalletEvent` (Java `sealed interface` of records, `domain`)

Java-authored by measurement (research Q-A/Q-B): a Scala `enum` can't be read back; a Scala `sealed trait`
isn't a JVM-sealed interface and fails startup validation. Each record carries `@TypeName`.

| Event | `@TypeName` | Fields | Emitted by |
|---|---|---|---|
| `Opened` | `opened` | `long startingBalance` | `open` |
| `Deposited` | `deposited` | `long amount`, `long balance` | `deposit` |
| `Withdrawn` | `withdrawn` | `long amount`, `long balance` | `withdraw` |
| `Closed` | `closed` | `long finalBalance` | `close` |

- Each event carries the **resulting balance** so `applyEvent` is a pure copy with no arithmetic — the
  arithmetic (and its validation) happened once, in the command handler, before persisting.
- `applyEvent` is total over the sealed interface and never fails (the invariant: validations precede
  persist).

## Entity — `WalletEntity` (Scala, `application`)

`EventSourcedEntity[Wallet, WalletEvent]`, `@Component(id = "wallet")`. Command handlers:

| Handler | Param | Returns | Notes |
|---|---|---|---|
| `open` | `Long` | `Effect[Done]` | rejects re-open / negative start |
| `deposit` | `Long` | `Effect[Done]` | rejects closed / non-positive |
| `withdraw` | `Long` | `Effect[Done]` | rejects closed / non-positive / overdraw |
| `close` | — | `Effect[Done]` | rejects closed |
| `getBalance` | — | `ReadOnlyEffect[Long]` | read-only; served from current state |

Snapshots: `akka.javasdk.event-sourced-entity.snapshot-every` (global). The capability states its value and
proves a wallet reloaded past the threshold equals a full replay (US3, FR-010).

## State transitions

```text
          open(start>=0)              close()
(empty) ─────────────────▶ OPEN ─────────────────▶ CLOSED
  │                         │  ▲                      │
  │ open(start<0) ✗         │  │ deposit(amt>0)       │ any op ✗
  │                         │  │ withdraw(0<amt<=bal) │
  └─ deposit/withdraw ✗     └──┘                      └─ (terminal)
     (not open)                open() ✗ (already open)
```

`✗` = rejected, no event persisted, balance unchanged (the event count does not advance — SC-002/SC-003).

## API types (in the endpoint, `api`) — never the domain `Wallet`

- `OpenRequest(startingBalance: long)`, `AmountRequest(amount: long)` — request bodies.
- `WalletView(balance: long, open: boolean)` — response for `GET`, via a private `toApi(Wallet)`.
