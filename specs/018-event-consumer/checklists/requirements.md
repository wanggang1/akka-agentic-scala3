# Specification Quality Checklist: Reacting to activity (Consumer)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-19
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — *see the deliberate deviation noted below*
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain — FR-001 resolved 2026-09-19 (option C)
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

- **Carried in from capability 15's review, up front rather than after the fact:**
  - **FR-009 — a stated retention bound.** Capability 15 shipped an in-process store that never evicted
    and had to add a known-limit note after review. This feed is in-process too, so its bound is a
    requirement from the start.
  - **FR-006/007 — bounded attempts that do not stall the stream.** Capability 15 found a platform
    retry that was unbounded *and* a bound that was silent. The consumer analogue is worse — a stuck
    message may block every later one — so containing it is a P1 story, not a polish item.
  - **FR-015 — restart behaviour measured, not assumed.** A feed emptied by a restart while the reaction
    does not replay would lose history silently; which of those happens decides whether in-process is
    honest here.
- **Deliberate deviation from "no implementation details":** *Why this capability exists* and *Open
  interop questions* name SDK mechanisms. This project's stated deliverable for every capability is a
  resolved interop finding, so the question being explored is itself user-facing scope — the same
  deviation every prior spec in `specs/` makes. Requirements and success criteria stay
  technology-agnostic.
- **One clarification, because it changes scope:** the source (FR-001). **Resolved: option C** — capability 6's
  to-do changes committed; capability 7's delegation tasks probed (Q-G) and added only at a user checkpoint.
- **Iteration 2 — reworded after the clarification:** FR-004, SC-002, US1 scenario 2 and the burst edge case
  first promised one ordered entry per change. The to-do source is a key-value source, which the SDK
  documents as delivering the *most recent* state, not every change. The promise is now "never out of
  order, latest state always arrives", with "every event, in order" reserved for the event-sourced task
  source if it is added. The other candidates had
  defensible defaults, recorded in *Assumptions*:
  - *Does the reaction involve a model?* **No** — recording a change needs none; calling an agent is a fork.
  - *Topic, feed, or both?* **Both** — the feed makes the reaction observable to a reader; publication is
    the "produce" half of the family and its interop is the untested part (Story 4). A real broker is out
    of reach locally, so publication is proven through the test toolkit's channel and stated as such.
  - *What happens to a message that can never succeed?* **Bounded, then set aside and recorded** — the
    only option consistent with "one bad message must not halt the capability".
- The six open interop questions (Q-A–Q-F) are **research**, not clarifications: resolved by measurement
  in `/akka.plan`, each with a stated fallback.
