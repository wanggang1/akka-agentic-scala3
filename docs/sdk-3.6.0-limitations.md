# SDK 3.6.x limitations — revisit on upgrade

This project pins **`akka-javasdk-parent` 3.6.3** (bumped from 3.6.0 on 2026-08-28). A few capabilities
hit **version-specific** SDK bugs or limitations — not design choices. Each was worked around and
documented in its feature spec; this page is the **single consolidated "re-check on upgrade" list**.

> **Re-tested on 3.6.3 (2026-08-28): all three below are STILL PRESENT — none fixed.** Confirmed by
> targeted spikes (#1 `Optional cannot be cast to Integer` on a supplied value; #2
> `Could not deserialize [json.akka.io/object]`; #3 `MemoryHistoryUtils.trimToLastN` bytecode is still a
> naive `subList(size−N, size)`). They almost certainly need **3.7+ / 3.15**, which is **gated behind a
> paid Akka plan** — see the version-ceiling note below.
>
> **Version ceiling on a FREE Akka subscription = 3.6.3.** The secure Akka artifact repo
> (`repo.akka.io/<token>/secure`) serves only **3.6.0–3.6.3** to a free-tier token; **3.6.4+ and all
> 3.7+/3.15 return a clean 404** (entitlement, not a broken release — the parent *metadata* is public on
> Central, so `versions:display-parent-updates` misleadingly advertises 3.15.19). So these items cannot be
> revisited on this SDK line without a **paid subscription**. `langchain4j` is BOM-managed to **1.11.6** by
> the 3.15.x parent (vs our pinned 1.15.0) — a conflict to reconcile *if/when* a paid plan unlocks 3.7+.

**When bumping the SDK** (only possible past 3.6.3 with a paid plan), do it on its **own branch** with a
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

**Re-tested on 3.6.3 (2026-08-28): STILL PRESENT.** A temporary `maxResults: Optional[Integer]` spike
supplying `maxResults=2` threw the identical `java.util.Optional cannot be cast to java.lang.Integer`
(omitted still returns 3). Unchanged.

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

**Re-tested on 3.6.3 (2026-08-28): STILL PRESENT.** A spike scripting `delegateTo(WeatherSpecialist, …)`
produced the identical `Could not deserialize message of type [json.akka.io/object] to type
[java.lang.String]` in `WeatherSpecialist.report`, and `DelegationOrchestrator` logged
`request-based delegation failed`. Unchanged.

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

**Re-tested on 3.6.3 (2026-08-28): STILL PRESENT.** Disassembling 3.6.3's
`akka.javasdk.impl.agent.MemoryHistoryUtils.trimToLastN` shows it is still `subList(size−N, size)` with no
tool-call/response pairing logic (no `ToolCall` references, no `dropWhile`/guard). Byte-for-byte the same
as 3.6.0.

**Restore on upgrade.** Check whether the SDK's trim became pair-aware; if so, `readLast(N)` can return as
a bounded window. The proper general bound is **compaction** (summarize old turns without slicing pairs),
which is worth doing regardless of the SDK — a separate future-work item. Detail: cap-6 "Live caveat" in
README §8 and `specs/008-*/`.
