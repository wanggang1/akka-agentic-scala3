# Learning Roadmap

A personal path for exploring **Akka agentic** capabilities on this Scala 3 + Akka Java SDK
service. Each capability is built as its own [spec-driven feature](specs/) so the work stays
small and reviewable. This page is the one-glance answer to *"what's done, what's next?"* — the
full design detail for any feature lives in its `specs/<id>/` folder.

## Where we are

> **You are here:** Feature 2 (multi-agent Workflow) — **implemented, in review** on branch
> `004-multi-agent-workflow`. Next up: capability 3, Autonomous Agent (not yet specced).

## The path

| # | Capability | Feature spec | Status |
|---|------------|--------------|--------|
| — | Baseline greeting agent (foundation) | [`specs/001-greeting-agent`](specs/001-greeting-agent/) | ✅ Done — merged |
| 1 | **Tools + structured output** — agent returns a typed `{greeting, tone, timeOfDay}` object and calls a `@FunctionTool` | [`specs/002-agent-tools-structured`](specs/002-agent-tools-structured/) | ✅ Done — merged (PR #5) |
| 2 | **Multi-agent Workflow** — orchestrate two agents (tone → compose) through an Akka `Workflow`; async start/poll HTTP. **Implemented in Java** (see below) | [`specs/004-multi-agent-workflow`](specs/004-multi-agent-workflow/) | 🚧 In review |
| 3 | **Autonomous Agent** — durable, model-driven process with typed tasks | _not yet created_ | ⬜ Not started |
| 4 | **Session memory** — multi-turn context across requests | _not yet created_ | ⬜ Not started |

**Status legend:** ✅ done · 📋 planned (spec written) · 🚧 in progress · ⬜ not started

> **Capability 2 is written in Java, not Scala.** The Akka `Workflow` API is keyed entirely on
> Java *method references* resolved from `SerializedLambda` — step wiring (`transitionTo`,
> `stepTimeout`, `RecoverStrategy.failoverTo`) **and** `WorkflowClient.method(...)`. There is no
> string/step-name overload and no `dynamicCall` on `WorkflowClient` (unlike agents), so a Scala
> lambda's mangled `$anonfun` name never resolves and a Scala workflow can't wire its own steps
> or be invoked. This is the workflow analogue of feature 003's two-mapper finding; the least-
> friction path is to write the whole capability in Java (`com.gwgs.akkaagentic.team.*`), fully
> decoupled from the Scala capability 1. See README "Scala interop notes" §4.

## Ideas / follow-ups

Not on the four-capability path, captured so they're not forgotten:

- **Make Jackson Scala-aware** — ✅ *done and merged (PR #7), [`specs/003-scala-native-json`](specs/003-scala-native-json/).*
  Registered `DefaultScalaModule` via an `@Setup` `Bootstrap`
  (discovered through a top-level `akka.javasdk.service-setup` descriptor entry). **Finding:** the
  SDK uses *two* Jackson mappers — the public one (`JsonSupport`) covers **HTTP endpoint bodies**
  only; **component payloads** (agent `Request`/`Result`, and by extension workflow state, entity
  events, view rows, task results) go through a *separate internal* mapper the public hook can't
  reach. So only HTTP DTOs (`GreetRequest`/`GreetReply`) went idiomatic-`Option`; everything
  component-serialized **stays Java-shaped**. Consequence: capabilities 2–4 below can't use
  idiomatic `Option` wire types either — keep them Java-shaped. See README "Scala interop notes" §3.

## Also merged along the way

Small additions made outside the four-capability path, useful as reference:

- **Input validation** — blank `user`/`text` and malformed JSON rejected with `400`, no model call (PR #3).
- **Health endpoint** — `GET /health`, added to prove descriptor-driven component discovery for Scala components (PR #4).

## How this doc is kept current

Updated only when a feature changes status (planned → in progress → done) — a handful of edits
per feature, folded into the feature's own workflow. If this table and the `specs/` folder ever
disagree, `specs/` is the source of truth.
