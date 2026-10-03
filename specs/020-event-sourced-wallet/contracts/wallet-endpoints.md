# Contract: Wallet HTTP endpoint

**Feature**: `020-event-sourced-wallet` · endpoint is **Java** (research Q-D: the entity client is
method-reference-only). Base path `/wallets`. `@Acl` as the project's other endpoints.

Request/response bodies are endpoint-local records; the domain `Wallet` is never returned (Constitution II).

## `POST /wallets/{id}/open`

Open a wallet with a starting balance.

- Body: `{ "startingBalance": 100 }`
- `201 Created` on success (nothing to return but the fact it was created).
- `400 Bad Request` if already open, or `startingBalance < 0`.

## `POST /wallets/{id}/deposit`

- Body: `{ "amount": 50 }`
- `200 OK` on success.
- `400 Bad Request` if the wallet is closed/unopened, or `amount <= 0`.

## `POST /wallets/{id}/withdraw`

- Body: `{ "amount": 30 }`
- `200 OK` on success.
- `400 Bad Request` if closed/unopened, `amount <= 0`, or `amount > balance` (overdraw).

## `POST /wallets/{id}/close`

- No body.
- `200 OK` on success.
- `400 Bad Request` if not open.

## `GET /wallets/{id}`

Read the current balance and open/closed state.

- `200 OK` with `{ "balance": 125, "open": true }` (`WalletView`, via `toApi`).
- A wallet with no events reads as `{ "balance": 0, "open": false }` (empty state), **not** a `404`.

## Notes

- Rejections map a domain error to `HttpResponses.badRequest(...)`; the handler never throws for a
  validation failure (AGENTS.md HTTP rule).
- The balance is read from the entity's folded state; a Java caller reads the reply as
  `((Number) reply).longValue()` because `ReadOnlyEffect[Long]`'s Scala `Long` erases to `Object` for the
  method-reference client (research Q-D bonus).
