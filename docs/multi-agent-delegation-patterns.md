# Multi-agent & delegation patterns — architecture follows the domain

A cross-capability design note for this project (companion to
[`http-endpoint-sdk-boundary.md`](http-endpoint-sdk-boundary.md)). It captures **how to decide** the
multi-agent topology for a feature, because the right shape is an *output of the domain*, not a default.

Written while building capability 7 (`specs/009-autonomous-delegation`); it generalizes the choices made
across capabilities 2, 6, and 7.

## The core rule

> **The multi-agent topology is domain-driven. You introduce a coordinator + specialist agents only when the
> domain has multiple distinct _reasoning_ specialties. Deterministic work stays a tool; "same behavior,
> different target" stays one agent addressed by instance.**

Decomposing for its own sake violates Simplicity (constitution IV). Ask what each capability actually *is*:

| A capability that is… | Model it as… | Not as… |
|---|---|---|
| **Deterministic** (CRUD, a lookup, a calculation) | a `@FunctionTool` (often over an entity) | an "agent" — a needless extra model call |
| **A distinct reasoning specialty** (compose a weather narrative, judge which activities fit, analyze a topic) | a **specialist agent** (a delegation worker) | a tool — it genuinely needs the model |
| **The same behavior for a different target** (act on _this_ user's data, forward to _another_ user's assistant) | the **same agent class, different instance/session id** | a new agent "type" |

Corollary: **promote a tool to a specialist agent only when it stops being deterministic and starts needing
judgment.** "Add a to-do" stays a tool forever; a hypothetical "plan my week around my goals" becomes an
agent.

## The topologies used in this project

| Cap | Domain | Topology | Why that shape | Language |
|---|---|---|---|---|
| **2** | greeting: tone → compose | **Workflow**, fixed 2-step sequence | Known, ordered steps; wanted an explicit step machine | Java (Workflow method-ref wall) |
| **3** | help desk: answer a question | **single `AutonomousAgent`**, model-driven loop (+ tools) | The model decides what to consult; one investigative agent, no other agents | Scala (no wall) |
| **5** | approval gate: draft → gate → publish | **`AutonomousAgent`s + task dependencies** (`dependsOn`); no Workflow | A *deterministic* ordered sequence with a human/external gate — dependency ordering, not a step machine | Scala (no wall) |
| **6** | personal assistant: to-dos + forward to another user | **one `Agent` + tools**; delegation = same class, different **username** (instance) | "Functions" are CRUD (tools); "delegation" is instance targeting, not a new specialty | Scala (`dynamicCall`) |
| **7** | activities: weather + activity suggestion | **coordinator (`AutonomousAgent`) + specialist `Agent`s** via `Delegation` | Two distinct *reasoning* jobs the model picks between at runtime | Scala (no wall) |

Same rubric, five different (correct) answers — each matches its domain. Note especially that **cap-2 and
cap-5 are *both* fixed/ordered sequences yet use different mechanisms** (Workflow vs AutonomousAgent task
chain), and **AutonomousAgent appears in three caps** doing three different things (single loop, deterministic
task chain, delegation) — so "AutonomousAgent ⇒ dynamic" is a false shortcut. Cap-6 is *not* under-engineered
for lacking specialists, and cap-7 is *not* over-engineered for having them.

## Choosing the coordination mechanism (when youʼve decided you need multiple agents)

Once the domain warrants multiple reasoning agents, pick the mechanism by *who decides what runs, when*:

- **Fixed, known sequence** → **two options**, and the choice is *not* "fixed vs dynamic":
  - **Workflow** — an explicit step state machine with compensation/retry per step. Choose it when you need
    that machinery. But the API is **Java-only** in this project (method-ref wall — README §4). This is cap-2.
  - **`AutonomousAgent` + task dependencies** (`dependsOn`) — a durable, dependency-ordered task chain with
    **no Workflow and no method-ref wall (stays Scala)**. The runtime never starts a task whose dependencies
    aren't met, so ordering is enforced without a step machine. This is **cap-5** (`draft → approval-gate →
    publish`), which is a *deterministic* sequence built on AutonomousAgents. Prefer this for ordered flows
    that don't need per-step compensation — especially in Scala. It also composes with an unassigned gate
    task for human/external input (cap-5).
  > So "AutonomousAgent" is **not** synonymous with "dynamic": with `dependsOn` it does fixed sequences too.
  > The real Workflow-vs-AutonomousAgent differentiator here is *step-machine/compensation + Java* (Workflow)
  > vs *dependency-ordered durable tasks + Scala* (AutonomousAgent), not fixed-vs-dynamic.
- **Model chooses among specialists, coordinator keeps ownership & synthesizes** → **`Delegation`**
  (`AutonomousAgent`). Dynamic **by class**: the model picks which declared worker type(s) to call. This is
  cap-7.
- **Model routes ownership to one specialist and steps out** → **Handoff** (`canHandoffTo`). Triage/routing.
- **Structured multi-party discussion** → **Moderation**; **self-organizing workers on a shared backlog** →
  **`TeamLeadership`**. (Not yet used here.)
- **Dynamic by _instance/runtime string_** (e.g. "forward to user *bob*") → **not a coordination capability
  at all** — call the target through the **agent `ComponentClient`'s `dynamicCall`** (cap-6). `Delegation`
  targets compile-time *classes*, so it cannot express by-username targeting; that was the key reason cap-6
  used hand-rolled chaining rather than a capability.

### `Delegation` vs cap-6 chaining — the head-to-head

|  | cap-6 (hand-rolled) | cap-7 (`Delegation`) |
|---|---|---|
| Wiring | our `ForwardTool` calls `forAgent().dynamicCall(...)` | declare `Delegation.to(A.class, B.class)`; framework provides the delegation tools |
| Target selection | by **username** (runtime string) | by **class**, model-chosen among declared workers |
| Result | relayed reply (verbatim) | coordinator **synthesizes** worker outputs |
| Ownership | caller stays in control manually | framework spawns worker, awaits, returns; coordinator retains task ownership |
| Blessing | SDK-*discouraged* chaining (taken deliberately) | SDK-*recommended* primitive |

Neither is "wrong" — they answer different questions (dynamic-by-instance vs dynamic-by-class).

## SDK fact worth knowing: what `Delegation.to(...)` accepts

Verified against SDK 3.6.0 bytecode:

```
Delegation.to(Class<? extends AgentDelegationWorker>, Class<? extends AgentDelegationWorker>...)
```

**Both** `akka.javasdk.agent.Agent` (request-based) **and** `akka.javasdk.agent.autonomous.AutonomousAgent`
implement the `AgentDelegationWorker` marker — so a delegation worker can be **either**:

- a **request-based `Agent`** — single model call; lighter; the worker's reply becomes the delegation tool's
  result. (cap-7's choice — Simplicity.) **Verified live** in cap-7 that this works (an unknown location
  returned the canned weather *default*, un-hallucinatable → the request-based worker genuinely ran).
- an **`AutonomousAgent`** — its own `TaskAcceptance` + a **typed, schema-validated** subtask result, and a
  durable per-subtask task record. The canonical `capabilities.html.md` form; heavier. Choose this when the
  worker needs its own tool loop, typed/validated results, or further delegation.

Rule of thumb: **request-based workers for single-call specialists; autonomous workers when a worker is itself
an investigation.**

### Testing caveat learned in cap-7 (SDK 3.6.0)

**Request-based delegation is not faithfully *mockable* offline** in the 3.6.0 testkit:
`TestModelProvider.AutonomousAgentTools.delegateTo(Class, String)` delivers a generic `json.akka.io/object`
payload the request-based worker cannot deserialize (fails against a `String` *and* a record). A delegation
mock silently **false-greens** (the WARN-level delegation failure doesn't fail the test). So test the
coordinator→task→typed-result path with a **direct** `completeTask` offline, and prove the **delegation
itself live** (as cap-4/cap-6 did for session-memory recall). The 3-arg autonomous-worker form
`delegateTo(Task, AutonomousAgent, String)` carries the task result type and *does* round-trip — another
reason to reach for autonomous workers if faithful offline delegation tests matter to you. Also:
`consultedSpecialists`-style **self-report is model-authored and unreliable on small models** — use the
runtime's delegation **notifications** for ground truth. (Both are logged as follow-ups.)

## cap-7 in code
- `src/main/scala/com/gwgs/akkaagentic/activities/application/ActivityCoordinator.scala` — `AutonomousAgent`,
  `TaskAcceptance.of(SUGGEST)` + `Delegation.to(classOf[WeatherSpecialist], classOf[ActivitySpecialist])`.
- `WeatherSpecialist.scala` / `ActivitySpecialist.scala` — request-based delegate `Agent`s.
- `ActivityTasks.scala` (the `SUGGEST` task) / `ActivitySuggestion.scala` (Java-shaped result) /
  `api/ActivityEndpoint.scala` (start-then-poll).

## See also
- Akka docs: `akka-context/sdk/autonomous-agents/capabilities.html.md` (Delegation, Handoff, Moderation,
  Teams, external input), `coordination.html.md` (patterns), `testing.html.md` (delegation mocking helpers).
- README "Scala interop notes" §4 (Workflow wall), §5 (AutonomousAgent no wall), §8 (cap-6 chaining), §9 (cap-7).
- `specs/009-autonomous-delegation/research.md` for the cap-7-specific decisions (D1/D6/D9).
