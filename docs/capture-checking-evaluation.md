# Evaluation: Scala 3 Capture Checking for agent safety

**Question raised:** could this project adopt Scala 3's **capture checking** (tracked capabilities) for
agent safety, per Martin Odersky's
["Tracked Capabilities for Safer Agents"](https://martinodersky.substack.com/p/tracked-capabilities-for-safer-agents)?

**Verdict (short):** **Not a fit as an agent-safety capability on this SDK** — the proposal targets a
different agent architecture (code-generating agents) and is blocked by the Akka SDK's reflective,
Java-interop core. A *narrow* pure-domain spike is possible as a Scala-3 feature demonstration, but it
would **not** deliver agent safety and must be framed honestly as such. For the actual goal (agent
safety on this SDK), the roadmap's **Guardrails** candidate is the pragmatic lever.

---

## What Odersky is proposing

The target is a **specific agent architecture: agents that generate and run code.** The TACIT harness
(evaluated on SWE-bench Lite / τ-bench) has the LLM *emit Scala source*, which is compiled with capture
checking enabled and can only touch the capabilities it was statically granted. Safety is a compiler
property: generated code that tries to exfiltrate data (`reveal` a `Classified[T]` without a
`CanAccess[T]` capability) or call an ungranted tool **does not type-check, so it never runs**.

Mechanism, conceptually:

- Capabilities are **tracked in types**; a value's type records which capabilities it can reach.
- **Pure** functions `T -> U` (capture nothing) are distinguished from **effectful** `T => U`.
- A `Classified[T]` wrapper exposes `reveal(using CanAccess[T]): T` and `transform[U](f: T -> U)` —
  classified data can only be mapped through **pure** functions, so nothing reaches the outside world.
- A **"safe subset"** *switches off the escape hatches* — **reflection, unchecked casts** — because those
  would defeat the guarantee.

> "Agent safety should be a property of the infrastructure, not a bet on how the model behaves."

## Why it does not fit this project

### 1. Architecture mismatch — tool-calling, not code-generating

This project's agents are **tool-calling agents.** The model emits structured JSON
(`{"tool":"retrieve","args":{…}}`); the **Akka SDK reflectively dispatches** it to a `@FunctionTool`
method or an MCP round-trip. There is **no agent-generated Scala source to capture-check** — the "program"
the model writes is a tool-call invoked by the runtime, not compiled. Capture checking has nothing to
attach to in the model→tool path. The headline safety property simply does not apply to how our agents
work.

### 2. The SDK is reflection-native (the safe subset disables exactly that)

Component discovery, tool dispatch, and Jackson (de)serialization are **all reflection**. Odersky's safe
subset *disables* reflection precisely because it is an escape hatch. You cannot obtain end-to-end capture
guarantees *through* the SDK boundary.

### 3. Java interop erases tracking at the SDK edge

Every SDK type we subclass or call is **Java** — untracked, treated as capturing `cap` (impure). Anything
touching `effects()`, `ComponentClient`, or the tool machinery becomes "impure/untracked," so the tracked
region stops at the SDK edge. This is the same boundary the project already documents (the two-mapper
finding, the method-reference wall) — now recurring for *effects/capabilities*.

### 4. Version + maturity

The project is on **Scala 3.3.8 LTS**. The `Classified` / pure-function-tracking form the article
demonstrates rides recent **nightlies (3.7+)**; `import language.experimental.captureChecking` on 3.3.x is
an early, far less capable form. The feature is **experimental with evolving syntax** — not a stable base
for a capability.

## Summary table

| Blocker | Consequence |
|---|---|
| Tool-calling (not code-gen) agents | Nothing for capture checking to attach to in the model→tool path |
| SDK is reflection-native | The "safe subset" disables reflection → no guarantee through the SDK |
| Java interop | SDK types are untracked `cap`; tracked region ends at the SDK edge |
| Scala 3.3.8 LTS, experimental feature | Article's form needs 3.7+ nightly; syntax unstable |

---

## (Optional) Scoped spike: capture checking in the pure `domain` layer

If the Scala-3 angle is interesting **for its own sake**, here is the only place it touches this repo
honestly — and the exact bounds of what it would and would not prove.

**Goal.** Demonstrate capture checking on the project's **pure `domain` layer** (which is already
Akka-free by convention), *not* on the agent runtime.

**What to build (small).**
- A throwaway module or `src/main/scala` package guarded by `import language.experimental.captureChecking`.
- Model a piece of domain data as `Classified[T]` — e.g. a RAG `Classified[Passage]` — and show that a
  transform is accepted only when it is a **pure** `T -> U` (e.g. compute a citation label) and **rejected**
  when it captures an effect (e.g. logs, or calls out).
- Optionally, annotate a domain function (`AskQuestion.validate`, a `KnowledgeCorpus` transform) and show
  the compiler *tracks* its purity.

**What it proves.** That Scala 3 can statically enforce purity/no-exfiltration **on domain values we
own and compile** — a real, interesting feature demonstration.

**What it explicitly does NOT prove (must be stated up front).** It does **not** make the *agents* safer:
the actual tool dispatch happens in SDK-controlled, reflective, Java runtime code that capture checking
cannot see. This is a language-feature spike, **not** an agent-safety capability.

**Prerequisites / cost.**
- A **Scala version bump** (likely a nightly 3.7+) to get the form in the article — done on its own branch,
  isolated from the LTS build, because it may not coexist cleanly with the mixed Java/Scala build settings
  (`-proc:none`, `-parameters`, `sendJavaToScalac=false`).
- Kept **out of the descriptor / off the agent path** — it's a compile-time demonstration, no runtime
  component.

**Exit criteria.** One file that type-checks a pure transform and *fails to compile* an impure one, plus a
short write-up of how far the tracking reaches before the SDK boundary erases it (tying back to
[`../FINDINGS.md`](../FINDINGS.md)).

**Recommendation.** Park this as a **low-priority, exploratory research spike**, distinct from the
agent-safety capabilities on the roadmap. Pursue **Guardrails** if the actual objective is safer agents.
