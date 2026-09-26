# Contract: session compaction (capability 17)

Two surfaces. The **compaction contract** is what happens to a session's history and is observed
indirectly; the **read surface** is the only HTTP this capability adds, and it is `GET`-only.

## The compaction contract

Compaction is not requested. It happens when a session's stored history crosses
`compaction.max-bytes`, and it applies to **every session in the service** — capability 4's chat,
capability 6's assistant and capability 14's streamed chat alike (FR-014). None of those capabilities is
modified.

| Guarantee | Statement |
|---|---|
| **Invisible when it works** | No turn fails, no reply changes shape, and no caller of capability 4, 6 or 14 sees a new field or status. |
| **Harmless when it fails** | A summariser that errors, times out or returns a blank summary leaves the history **exactly as it was**; the turn still returns a normal reply (FR-005). |
| **Never mid-turn** | The trigger fires on `AiMessageAdded`, the event that ends a turn and the only one carrying the running size (research S-5). A compaction can never land between a tool call and its response. |
| **Never a split pair** | The replacement is two prose messages, so a tool-call pair cannot survive to be orphaned (FR-003). |
| **Verified, not assumed** | A stale write is accepted **silently** by the platform (research R-4), so the outcome is confirmed by re-reading the history. `Compacted`, `SkippedStale` and `Failed` are three different recorded facts. |
| **Below the SDK's own bound** | `compaction.max-bytes` is range-enforced to stay well under the SDK's 510 KiB eviction (research S-1). Above it, eviction would discard the oldest turns before compaction ever ran. |

**What is lost, stated plainly**: the summarised turns are gone. Compaction replaces history; it does not
archive it. That is a better outcome than the SDK's default — which discards the oldest turns and leaves
*nothing* behind — but it is not lossless, and nothing recovers the originals.

## `GET /compaction` — every session this process has compacted

```json
{"since":"2026-09-26T13:04:11Z",
 "sessions":[
   {"sessionId":"alice","compactions":2,"lastBytesBefore":131072,"lastBytesAfter":214,
    "lastMessagesReplaced":48,"lastOutcome":"compacted","lastAt":"…"},
   {"sessionId":"c-123","compactions":1,"lastBytesBefore":132110,"lastBytesAfter":198,
    "lastMessagesReplaced":36,"lastOutcome":"compacted","lastAt":"…"}]}
```

`lastOutcome` ∈ `compacted` · `skipped-stale` · `failed`. An empty list is a **success**, not a `404` —
"nothing has needed compacting" is a valid answer. Row order is unspecified.

`since` is the moment this process began recording. The ledger is **in-process**: a restart empties it
while the compacted histories themselves survive in the entity, so `since` says which window the numbers
cover — the same honesty capabilities 15 and 16 ship.

## `GET /compaction/{sessionId}` — one session

```json
{"sessionId":"alice","compactions":2,"lastBytesBefore":131072,"lastBytesAfter":214,
 "lastMessagesReplaced":48,"lastOutcome":"compacted","lastAt":"…"}
```

Three answers that must not be collapsed:

```text
GET /compaction/alice        200  — this session has been compacted, here is what happened
GET /compaction/never-seen   404  — no record; either it never crossed the threshold or this
                                    process restarted since (see `since` on the collection)
GET /compaction/%20%20       400  — sessionId must not be blank; no lookup runs
```

`404` deliberately does **not** distinguish "never needed compacting" from "lost to a restart", because
this capability cannot tell the difference — the entity holds no record of who compacted it. Saying so is
better than guessing.

## Configuration

| Key | Env | Default | Rule |
|---|---|---|---|
| `compaction.max-bytes` | `COMPACTION_MAX_BYTES` | `131072` (128 KiB) | 1 KiB – 256 KiB, enforced. Must stay below the SDK's 510 KiB eviction (S-1) |
| `compaction.enabled` | `COMPACTION_ENABLED` | `true` | `false` reproduces capability 6's behaviour exactly, for reproducing a problem or paying for full history |
| `compaction.max-sessions` | `COMPACTION_MAX_SESSIONS` | `1000` | Ledger retention; least-recently-changed evicted first |

An out-of-range value is the **server's** fault: the request that trips it answers `500` with a correlation
id and a `ConfigException$BadValue` in the log — not a `400`, which would blame the caller for the
operator's configuration (capability 15, `docs/sdk-3.6.0-limitations.md` §7d).

## Not in this contract

- **No way to request compaction.** There is no `POST`. Compaction is a property of the memory, not an
  operation a caller performs — and a manual trigger would be a second code path to keep correct.
- **No archive endpoint.** Nothing recovers summarised turns; see above.
- **No per-capability scoping.** FR-014 decided service-wide, because scoping would mean matching session
  ids by convention — capability 6 keys them by username, capabilities 4 and 14 by opaque ids.
