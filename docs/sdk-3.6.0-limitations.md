# SDK 3.6.0 limitations — revisit on upgrade

This project pins **`akka-javasdk-parent` 3.6.0**. A few capabilities hit **version-specific** SDK bugs
or limitations — not design choices. Each was worked around and documented in its feature spec; this page
is the **single consolidated "re-check on upgrade" list**.

**When bumping the SDK** (the runtime has reported 3.6.1+ available), do it on its **own branch** with a
full `mvn verify` across all capabilities plus live spot-checks — the bump touches every capability. For
each item below, check whether the newer SDK fixes it and, if so, **restore the fuller behavior and its
tests**, then remove that entry here. When the list is empty, delete this file.

---

## 1. MCP `@McpTool` — no tunable optional numeric parameter (cap-9, feature 011)

**Symptom.** An optional numeric tool argument (cap-9's `maxResults`) cannot be expressed on 3.6.0. All
three shapes fail — proven against the TestKit in feature 011 T006:

| Shape | Result |
|-------|--------|
| `Optional[Integer]` bare param *(the doc-recommended non-required shape)* | A **supplied** value throws `"class java.util.Optional cannot be cast to class java.lang.Integer"`. The cast is Optional→element, so it fails for **every** element type. Omitted works; any value fails. |
| plain `Integer` bare param | Supplied values bind, but the SDK marks a non-`Optional` object param **required** → omitting it fails with `"Missing required tool parameter [maxResults]"`. The exact inverse. |
| manual `inputSchema` over two bare params | Breaks argument binding entirely (`"argument type mismatch"`) — the manual-schema path is tied to a single wrapper-record param, incompatible with bare multi-params. |

This is an **SDK bug/limitation, not a Scala-interop wall** — the reflective bare-param path itself works
(single `question` param is fine).

**Current workaround.** `KnowledgeMcpEndpoint.retrieve` takes only `question` and returns a **fixed
top-K of 3**, exactly mirroring cap-8's `DocsEndpoint`. A faithful cap-8 mirror, not a loss of retrieval
quality.

**Restore on upgrade.** Re-add the optional `maxResults` (try `Optional[Integer]` first — verify the cast
bug is gone), and restore the clamping tests: omitted → 3, `1` → 1, `0`/negative → floor 1, `9999` →
whole corpus. Deviation notes live in `specs/011-mcp-knowledge-server/` (spec SC-006/FR-006, contract §3,
data-model banner, research R2) and in the `KnowledgeMcpEndpoint` scaladoc.

---

## 2. Request-based delegation not faithfully mockable offline (cap-7, feature 009)

**Symptom.** The 3.6.0 testkit's `AutonomousAgentTools.delegateTo(Class, String)` (the request-based
worker form) delivers an untyped `json.akka.io/object` payload the worker cannot deserialize, so a
delegation mock is a silent **false-green** (the WARN-level failure doesn't fail the test). The 3-arg
**autonomous**-worker form round-trips fine; only the 2-arg request-based form is affected. **Live
delegation is unaffected** — the real runtime tags the payload with the worker's type.

**Current workaround.** cap-7's offline test uses a **direct** completion (no delegation mock); delegation
itself is proven **live** (an unknown location returns `WeatherData`'s un-hallucinatable canned default).

**Restore on upgrade.** Re-add a delegation mock to `ActivityCoordinatorIntegrationTest` (coordinator
`delegateTo(...)` both specialists → each `fixedResponse` → coordinator `completeTask` on the "Continue
working" turn) and confirm the workers deserialize (no `json.akka.io/object` WARN). Detail:
`specs/009-autonomous-delegation/` research D9.

> **Companion (not version-limited):** cap-7's `consultedSpecialists` is model self-report and flaky on
> small models. The durable fix is asserting on the runtime's **notification** stream for ground-truth
> delegation records (see `akka-context/sdk/autonomous-agents/notifications.html.md`) — independent of the
> SDK version, but a good thing to tackle alongside this item.

---

## 3. `MemoryProvider.readLast(N)` orphans tool-call pairs (cap-6, feature 008)

**Symptom.** SDK 3.6.0 `MemoryHistoryUtils.trimToLastN` is a naive `subList(size-N, size)` that ignores
tool-call/response pairing. Once a tool-using session exceeds N messages the window head becomes an
**orphaned `ToolCallResponse`**, an invalid chat sequence the runtime rejects during request assembly —
surfaced misleadingly as `argument "content" is null`. Proven live in cap-6 by an A/B (removing `readLast`
fixes it).

**Current workaround.** `PersonalAssistantAgent` uses **full session history**
(`MemoryProvider.limitedWindow()`, no `readLast`), accepting unbounded token growth on long sessions.

**Restore on upgrade.** Check whether the SDK's trim became pair-aware; if so, `readLast(N)` can return as
a bounded window. The proper general bound is **compaction** (summarize old turns without slicing pairs),
which is worth doing regardless of the SDK — a separate future-work item. Detail: cap-6 "Live caveat" in
README §8 and `specs/008-*/`.
