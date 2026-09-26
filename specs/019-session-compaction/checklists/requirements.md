# Specification Quality Checklist: Session compaction

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-26
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

**Iteration 1 — one marker, raised rather than guessed. Iteration 2 — resolved; all items pass.**

`FR-014` asked whether compaction applies to **every** session in the service or **only** capability 6's.
It was raised rather than defaulted because it changes both what gets built and what gets touched, and
this project has consistently treated "capability N is untouched" as a deliverable (capability 11 proved
it with `git diff`; capability 13 had to justify a one-line change to capability 8).

**Answered 2026-09-26: all sessions in the service.** Compaction is a property of the service's memory,
not of one agent. The rejected alternative would have scoped by session-id convention — capability 6 keys
sessions by username, capabilities 4 and 14 by opaque ids — which is fragile enough that it would have
shipped as a documented weakness. The decision adds **SC-009**: capabilities 4, 6 and 14 must all pass
their existing tests unmodified, and a session from each must be shown to compact. Q-D in the spec is
marked resolved-by-decision, and what remains for planning to *measure* is the consequence — whether
capability 14's streamed turn tolerates its history being replaced mid-assembly.

Two other candidates were resolved as assumptions rather than questions, per the "reasonable default"
rule:

- **Threshold unit** → bytes, because the SDK reports history size in bytes on the triggering event; a
  message count would require reading the history first (see Q-E).
- **Observability** → a small read-only surface rather than log output, because FR-011 must be testable
  and SC-001 must be measurable, and every other capability in this repository has one.

Interop questions Q-A–Q-E are **not** clarification markers. They are measurements to be taken in
planning by a discovery probe, which is this project's established sequence — every capability from 13
onward had its predictions corrected by one.
