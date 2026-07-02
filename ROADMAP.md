# Learning Roadmap

A personal path for exploring **Akka agentic** capabilities on this Scala 3 + Akka Java SDK
service. Each capability is built as its own [spec-driven feature](specs/) so the work stays
small and reviewable. This page is the one-glance answer to *"what's done, what's next?"* — the
full design detail for any feature lives in its `specs/<id>/` folder.

## Where we are

> **You are here:** Feature 1 (structured output + tools) — **planned, ready to implement.**
> Baseline greeting agent is done and merged.

## The path

| # | Capability | Feature spec | Status |
|---|------------|--------------|--------|
| — | Baseline greeting agent (foundation) | [`specs/001-greeting-agent`](specs/001-greeting-agent/) | ✅ Done — merged |
| 1 | **Tools + structured output** — agent returns a typed `{greeting, tone, timeOfDay}` object and calls a `@FunctionTool` | [`specs/002-agent-tools-structured`](specs/002-agent-tools-structured/) | 📋 Planned — SDD docs committed, code not started |
| 2 | **Multi-agent Workflow** — orchestrate two agents through an Akka `Workflow` | _not yet created_ | ⬜ Not started |
| 3 | **Autonomous Agent** — durable, model-driven process with typed tasks | _not yet created_ | ⬜ Not started |
| 4 | **Session memory** — multi-turn context across requests | _not yet created_ | ⬜ Not started |

**Status legend:** ✅ done · 📋 planned (spec written) · 🚧 in progress · ⬜ not started

## Also merged along the way

Small additions made outside the four-capability path, useful as reference:

- **Input validation** — blank `user`/`text` and malformed JSON rejected with `400`, no model call (PR #3).
- **Health endpoint** — `GET /health`, added to prove descriptor-driven component discovery for Scala components (PR #4).

## How this doc is kept current

Updated only when a feature changes status (planned → in progress → done) — a handful of edits
per feature, folded into the feature's own workflow. If this table and the `specs/` folder ever
disagree, `specs/` is the source of truth.
