# Specification Quality Checklist: Agent Guardrails (cap-12)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-03
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — *with a documented, deliberate exception; see Notes*
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders — *Requirements and Success Criteria are; Context is not; see Notes*
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain (zero were needed — all gaps closed by informed defaults, recorded in Assumptions)
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic — *SC-009 is the documented exception; see Notes*
- [x] All acceptance scenarios are defined (4 user stories, 9 scenarios)
- [x] Edge cases are identified (5, including the decline-vs-block collision)
- [x] Scope is clearly bounded (explicit Out of Scope, 6 exclusions)
- [x] Dependencies and assumptions identified (9 assumptions)

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows (request-side block, response-side block, record-only, declarative attachment)
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification — *beyond the documented exception*

## Notes

**Documented deviation — the interop finding is a first-class deliverable, not an implementation
detail.** This repository is a learning sandbox whose stated purpose (README, `FINDINGS.md`) is to
resolve, one capability at a time, which Akka SDK component families can be authored in Scala 3 on
the Java-first Java SDK. The *resolved interop finding* is therefore a user-facing outcome on equal
footing with the feature, which is why FR-013 and SC-009 name Scala and the component descriptor
explicitly, and why the Context section discusses reflective class loading. The same deviation was
taken deliberately in `specs/013-views-read-model/spec.md` and is house style here.

Everything else is held to the standard: **FR-001 through FR-012 and SC-001 through SC-008 are
technology-agnostic** and readable by a non-technical stakeholder — they describe refusals,
declines, blocks, records, and auditability, never a class, an API, or a config key.

**Two items were resolved by informed default rather than by asking**, and are recorded in
Assumptions so planning can revisit them:
1. *Which agent to guard* — capability 8's `DocsAgent`, because its grounding is already documented
   as an unenforced soft instruction, so governance has something real to attach to.
2. *How a block is surfaced* — as an unprocessable request naming the rule and category, distinct
   from both an answer and an honest decline. The spec requires the **distinguishability** (FR-005);
   the exact status code is left to planning.

**One risk is deliberately carried into planning rather than assumed away**: capability 8's agent
converts any failed turn into the honest-decline sentinel, which would swallow a governance block
and violate FR-005. Whether it actually does is a research question for `/akka.plan`, flagged in
both Edge Cases and Assumptions.
