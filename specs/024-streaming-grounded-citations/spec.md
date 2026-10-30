# Feature Specification: Streaming grounded answer with honest citations

**Feature Branch**: `024-streaming-grounded-citations`
**Created**: 2026-10-29
**Status**: Draft
**Input**: User description: "Fork B5 — stream a retrieval-augmented answer AND cite its sources honestly. New parallel SSE surface reusing capability 20's framing (event: data / event: error) plus a terminal event: citations frame carrying capability 21's honesty floor (reported-used ∩ retrieved). Capabilities 8, 14, 20, 21 left untouched as baselines."

## Context & background *(why this exists)*

Two capabilities on this service each solved half of this problem and were deliberately kept apart:

- **Streaming (capabilities 14 and 20).** A caller can POST a question and read the answer *as it is
  produced*, fragment by fragment. Capability 20 (fork B2) wraps that stream as Server-Sent Events with
  explicit `event: data` / `event: error` frames, so a failure is self-describing instead of a silently
  empty `200`. Neither streaming surface is grounded — they are a plain conversation, not RAG.
- **Grounded, honestly-cited answers (capabilities 8 and 21).** A caller can ask a question answered from
  a local document corpus and get back the answer plus citations. Capability 21 (fork B4) made those
  citations *honest about usage*: it cites only the sources the model reports **using**, intersected with
  what was actually **retrieved** — the "honesty floor" (`reported ∩ retrieved`), which can only narrow
  the ground-truth set, never invent a citation. Neither grounded surface streams — the answer arrives
  whole.

This capability (roadmap **fork B5**) joins the two: **stream a grounded answer, and cite it honestly.**
It was forked out of capability 14 on purpose, and recorded as future work in that spec's Assumptions and
in its stream-chat contract, because it carries **two intrinsic conflicts that are design problems, not
new SDK mechanics** (both halves already build in Scala):

1. **A tool call *inside* a live stream is undocumented on this SDK.** Retrieval normally happens as a
   step before the model answers; doing it mid-stream is not a documented pattern here.
2. **Honest citations cannot follow prose that has already been sent.** The set of sources the model
   *used* is only known *after* generation — but a stream has already delivered the text by then. This
   collides head-on with the honesty floor: you cannot retroactively annotate words the caller has read.

**The central design decision this capability makes:** a *trailing, separately-labeled* citation frame —
a distinct terminal `event: citations` emitted after the prose frames and before the stream closes — is
**honest**, because it is a new, clearly-typed event the caller receives and parses on its own terms, not
a rewrite of prose already delivered. This is the opposite of the dishonest move capability 14's spec
warned against ("appending citations after the text has gone out" as if they had always been part of it).
The distinction being asserted, and which this capability must demonstrate, is: *a distinct terminal
event ≠ silently altering delivered text.*

The chosen approach, accordingly, sidesteps conflict #1 and resolves conflict #2 by framing:

- **Retrieve up-front, before the stream opens** (documented and trivial — the escape hatch for #1).
- **Stream the grounded prose** as `event: data` frames (capability 20's surface, unchanged in shape).
- **At stream close, compute the honesty floor and emit it as a third event type** (`event: citations`),
  alongside the ground-truth retrieved list so divergence stays visible (as capability 21 does).

**How the "used sources" are known at all over a stream (the core mechanism).** A streamed turn delivers
only text — there is no structured field to carry a `usedSources` list the way capability 21's
non-streamed turn does. The only reliable signal of which sources the model used, inside streamed text,
is the inline `[n]` source marker the model already emits (capability 21's R6 finding). So the model is
asked to write those markers; the **server reads them as a private signal** — accumulating the streamed
text, parsing the `[n]` numbers, and resolving them against the retrieved set — and then **strips the
markers from the prose before forwarding each `data` frame**, so the caller reads clean prose and sees
citations only in the terminal `citations` frame. The frame, not the prose, is the single authoritative
citation source: it alone applies the honesty floor (an out-of-range or non-retrieved marker is dropped),
maps each number to a source label, and carries the retrieved list for comparison. This is why clean
prose plus a terminal frame — rather than visible inline markers — is the honest design: the prose never
shows a raw, unverified marker that the floored frame would then silently contradict.

Capabilities 8, 14, 20, and 21 — their endpoints, agents, and contracts — are left **completely
untouched**. This is a new parallel surface; no existing caller pays for it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A grounded answer that streams and then cites honestly (Priority: P1)

A caller asks a corpus question and reads the grounded answer as it streams in, then — once the prose is
complete — receives a distinct, clearly-labeled citations frame naming only the sources the answer
actually used (bounded by what was retrieved), with the full retrieved set alongside for comparison.

**Why this priority**: This is the whole point of B5 — streaming *and* honest grounding in one surface.
Without it there is no capability.

**Independent Test**: Call the new endpoint with the corpus question known (from capability 8's README
example, *"what makes agent work survive a restart?"*) to answer from one of several retrieved passages.
Assert the concatenated `data` frames reconstruct the grounded answer, and that a terminal `citations`
frame arrives carrying (a) a usage-accurate citation list that is a subset of the retrieved set and
(b) the full retrieved set — proving the honesty floor travels over the streaming surface.

**Acceptance Scenarios**:

1. **Given** a corpus question whose answer uses fewer than all retrieved passages, **When** the caller
   consumes the stream, **Then** the ordered `data` frames reconstruct the grounded answer and a single
   terminal `citations` frame carries the usage-accurate citation list (reported ∩ retrieved) plus the
   ground-truth retrieved list.
2. **Given** the stream has completed successfully, **When** the caller inspects the frame order, **Then**
   the `citations` frame arrives **after** all `data` frames and the text in the `data` frames is never
   altered or re-sent to carry citations (the citation signal is a separate event, not a rewrite).

---

### User Story 2 - An honest decline streams and cites nothing (Priority: P1)

A caller asks a question the corpus does not cover. The assistant declines, and the citations frame cites
nothing — no fabricated sources — exactly as capabilities 8 and 21 guarantee, even though the answer was
streamed.

**Why this priority**: The honesty guarantee must not regress because the surface now streams. A decline
that streamed a citation would be strictly worse than the non-streaming surfaces.

**Independent Test**: Ask an out-of-corpus question; assert the streamed answer is the decline and the
terminal `citations` frame carries an empty usage-accurate list (a decline overrides any reported usage).

**Acceptance Scenarios**:

1. **Given** an out-of-corpus question, **When** the caller consumes the stream, **Then** the prose is the
   decline and the `citations` frame cites nothing.
2. **Given** the model names source labels on a decline, **When** the citations frame is built, **Then**
   it still cites nothing (a decline overrides reported usage — capability 21's rule, preserved).

---

### User Story 3 - A failure mid-stream is self-describing, not a half-answer (Priority: P1)

The agent fails — before the first fragment, or after some prose has streamed. The caller receives an
explicit `error` frame and can tell the answer is incomplete, rather than seeing a prose stream that just
stops (capability 20's guarantee, which B5 must not lose).

**Why this priority**: Streaming grounding adds a second failure surface (retrieval can fail before the
model is even called). The self-describing-failure property capability 20 established is a floor this
capability inherits and must keep; a grounded surface that fails silently would be a regression.

**Independent Test**: Force a failing model turn (offline, via the test model provider) and assert an
`event: error` frame is emitted with a stable, client-safe reason and no leaked internals; then force a
pre-stream retrieval failure and assert it is surfaced the same honest way (error frame, no citations
frame implying a grounded answer that never happened).

**Acceptance Scenarios**:

1. **Given** the model fails after some `data` frames, **When** the stream ends, **Then** an `error` frame
   is emitted before close and no `citations` frame is sent (there is no honest citation set for an answer
   that did not complete).
2. **Given** retrieval fails before the stream opens, **When** the caller consumes the stream, **Then** the
   failure is surfaced as an `error` frame with a client-safe reason, not as an empty or misleading
   grounded answer.

---

### User Story 4 - Measure whether end-of-stream self-report matches the batch path (Priority: P2)

An evaluator (the project itself, recording findings) wants to know whether the model reports its
used-sources the **same** at the end of a *streamed* turn as it does on capability 21's non-streamed RAG
path — because that equivalence is assumed but has never been measured on this stack.

**Why this priority**: B5's honesty rests on reusing B4's floor over a streamed turn. If a streamed turn
degrades the self-report (e.g. the model emits markers only in prose and never populates the structured
field when streaming), the floor would quietly cite nothing more often. The capability's real deliverable
includes *measuring* this, not assuming it — consistent with how B4 was built.

**Independent Test**: Run a small set of in-corpus questions live against the local model over the
streaming surface and record, per question, the reported used-sources and the retrieved set; compare with
capability 21's documented results on the same questions. Write the finding into `research.md` and the
README section, stating it as evidence (and as "not equivalent" if that is what is found).

**Acceptance Scenarios**:

1. **Given** a live in-corpus question over the streaming surface, **When** the stream completes, **Then**
   the terminal citations frame carries both the self-reported used-sources and the ground-truth retrieved
   set, so divergence is observable from the stream alone.
2. **Given** the same questions were measured on capability 21's non-streamed surface, **When** the
   streamed results are compared, **Then** the capability records whether the streamed self-report is
   consistent with the batch path, with the evidence shown rather than assumed.

---

### Edge Cases

- **Model returns a non-decline answer but names no usable sources** (none, or only non-retrieved labels)
  → the prose streams normally and the `citations` frame carries an **empty** usage-accurate list with the
  retrieved list still shown (capability 21 FR-007, preserved over the stream).
- **Model names a source that was not retrieved** (an out-of-range or fabricated `[n]`) → dropped from
  the usage-accurate citations (the structural honesty floor: a source never offered cannot have been
  used); it may be recorded for the measurement but is never cited. Because the marker is stripped from
  the prose, the caller never sees this unverified marker contradict the floored frame.
- **A marker is split across a stream chunk boundary** (one chunk ends `"...restart [1"`, the next starts
  `"2] because…"`) → the stripping MUST still remove it cleanly and reconstruct the correct number; a
  partial marker MUST NOT leak into the delivered prose. This is the one subtle correctness point of the
  strip (see plan risks).
- **Failure after the `citations` frame would have been computed but the connection drops** → the caller
  sees an incomplete stream (no clean terminal frame); as with capability 20, an incomplete body must be
  treated by the client as failure. This is a property of streaming, stated not papered over.
- **Blank or absent question** → rejected with a validation error before any retrieval or stream opens
  (mirrors capabilities 8 and 21).
- **Empty but successful answer** → distinguishable from failure by the presence of the clean terminal
  citations frame (an empty answer still completes and still cites, honestly, whatever the floor yields).
- **Guardrail refusal or malformed structured output** → treated as capability 21 treats them (a refusal
  is not a decline; malformed output is a failed turn, surfaced as an `error` frame, not a 500).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a **new** streaming, grounded question-answering surface that leaves
  capabilities 8, 14, 20, and 21 — their endpoints, agents, and response contracts — completely unchanged.
- **FR-002**: The system MUST retrieve grounding passages from the same local corpus **before the stream
  opens**, and hand them to the model as context (reusing capability 8's retrieval; no in-stream tool
  call, no second corpus).
- **FR-003**: The system MUST deliver the grounded answer incrementally as Server-Sent Events, with each
  generated text fragment carried in an `event: data` frame (capability 20's surface shape), sent as it is
  produced rather than buffered whole.
- **FR-004**: The ordered concatenation of the decoded text in all `data` frames MUST reconstruct the
  agent's grounded answer **with the inline source markers removed** — i.e. the clean prose a reader
  should see. The markers are a private server-side signal (FR-006), never part of the delivered answer.
- **FR-005**: After all `data` frames and before the stream closes, the system MUST emit a **single,
  distinct terminal `event: citations` frame** carrying the honest citation result. This frame is the
  **only** place citations appear; the delivered prose MUST NOT carry visible citation markers, and prose
  already sent MUST NOT be altered or re-sent to add citations.
- **FR-006**: The system MUST derive the model's used-sources from the inline `[n]` markers the model
  emits in its answer (the only signal available over a stream — there is no structured field), by
  accumulating the streamed text and parsing the numbers server-side. The `citations` frame MUST carry
  the usage-accurate citation list computed as those reported numbers **intersected with** the retrieved
  sources (capability 21's honesty floor: a cited source is always one that was genuinely retrieved),
  resolved to source labels, **and** the ground-truth retrieved list, so divergence between used and
  retrieved is visible from the stream alone.
- **FR-007**: When the assistant declines (the corpus does not answer the question), the `citations` frame
  MUST cite nothing, regardless of any sources the model reported — a decline overrides reported usage.
- **FR-008**: When the model returns a non-decline answer but reports no usable sources (none, or only
  non-retrieved labels), the `citations` frame MUST carry an **empty** usage-accurate list while still
  showing the ground-truth retrieved list (self-report failure stays visible, not masked by the full
  retrieved set).
- **FR-009**: When the agent fails — **before** the first fragment (including a pre-stream retrieval
  failure) or **after** some `data` frames — the system MUST emit an explicit `event: error` frame before
  ending the stream, and MUST NOT emit a `citations` frame for an answer that did not complete; a failure
  is always distinguishable from a successful (including successfully empty) answer.
- **FR-010**: The `error` frame MUST carry a stable, client-safe reason and MUST NOT leak internal
  implementation detail (stack traces, internal class/exception names) — capability 20 FR-006, preserved.
- **FR-011**: Fragment content containing newlines or other SSE-significant characters MUST be framed so
  the client reconstructs the original text exactly (capability 20 FR-007, preserved).
- **FR-012**: A blank or absent question MUST be rejected with a validation error before any retrieval or
  stream opens (mirrors capabilities 8 and 21).
- **FR-013**: Each question MUST be answered independently (no cross-question memory), as the grounded
  surfaces do.
- **FR-014**: The endpoint MUST carry an access-control annotation consistent with the project's other
  streaming/chat endpoints.
- **FR-015** *(documentation / measurement)*: The project documentation MUST record (a) the design
  argument for why a distinct terminal citation frame is honest where appending to delivered prose would
  not be, and (b) a measurement of whether the model's end-of-stream self-report of used-sources is
  consistent with capability 21's non-streamed path on this stack — stated as evidence, including "not
  equivalent" if that is the finding.

### Key Entities *(include if feature involves data)*

- **Grounded answer with used-sources**: the model's reply over retrieved context — streamed text plus the
  set of source labels the model reports using (or the decline sentinel). The used-sources signal is read
  at the end of the turn (reused from capability 21).
- **Retrieved passage**: a corpus passage offered as context before the stream opens, carrying its source
  label (reused from capability 8). The set of these labels is the ground-truth retrieved list.
- **Stream frame**: a Server-Sent Event of one of three types — `data` (a text fragment of the answer),
  `citations` (the single terminal honest-citation result + retrieved list), or `error` (a stable,
  client-safe failure reason). Exactly one of `citations` or `error` terminates a given stream.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a question whose grounded answer streams successfully, the concatenated decoded `data`
  frames reconstruct the full answer and exactly one terminal `citations` frame follows them — in 100% of
  successful streams.
- **SC-002**: Every source in the `citations` frame's usage-accurate list was, for that same request,
  among the retrieved passages (no cited source was never offered) — in 100% of responses.
- **SC-003**: An out-of-corpus question streams the decline and cites zero sources, with no fabricated
  citation — in 100% of decline responses.
- **SC-004**: A failure before or during the stream is surfaced as an `event: error` frame with a
  client-safe reason and **no** `citations` frame — demonstrated for both a pre-stream retrieval failure
  and a mid-stream model failure.
- **SC-005**: Capabilities 8, 14, 20, and 21 are unchanged: their existing tests pass without
  modification.
- **SC-006**: The capability ships a documented measurement of whether the model's end-of-stream
  used-source self-report is consistent with capability 21's non-streamed path, over a small set of live
  in-corpus questions — stated as evidence in the docs.
- **SC-007**: The capability's behavior is proven without a live model (offline-deterministic for frame
  ordering, the honesty floor, decline, and failure framing), or the inability to prove any part offline
  is documented with what was tried and what proves it instead.

## Assumptions

- **Approach: retrieve up-front, stream prose, cite in a terminal frame (recommended default).** The
  capability retrieves before the stream opens (sidesteps the undocumented in-stream tool call), streams
  the grounded prose, and emits the honesty floor as a terminal `event: citations` frame on capability
  20's SSE surface. Two recorded alternatives — streaming inline `[n]` markers resolved at close, and a
  two-phase (fix-sources then stream-constrained-prose) split — are **not** taken; they are noted in Out
  of scope. *(This is the defining design choice of B5; confirm before planning.)*
- **A distinct terminal event is honest; rewriting delivered prose is not.** The capability asserts and
  must demonstrate that a separately-typed `citations` frame the caller parses on its own terms is not the
  dishonest "appending citations after the text has gone out" that capability 14's spec warned against.
- **New surface = new endpoint + new agent method.** A request-based Agent allows one command handler and
  the existing grounded agents return their own shapes; a streaming, usage-reporting answer is a separate
  component/method, leaving capabilities 8 and 21's agents untouched (implementation detail; confirmed at
  planning).
- **The used-sources signal is the inline `[n]` marker, parsed server-side — not a structured field.** A
  streamed turn has no structured output, so capability 21's structured `usedSources` list is unavailable
  here (verified Phase-0 finding). The model is asked to write the 1-based source number `[n]` inline (the
  signal it reliably emits — B4's R6), the server parses those numbers from the accumulated streamed text,
  and resolves them against the retrieved set using capability 21's `RetrievedSources` value type (the
  structural floor drops out-of-range numbers). The markers are then stripped from the delivered prose.
  Whether this reads the same at end-of-stream as on capability 21's non-streamed path is exactly what
  FR-015/SC-006 measure.
- **Clean prose by stripping; the terminal frame is authoritative (user decision, 2026-10-29).** Of the
  three considered mechanisms — visible markers, stripped markers, or two separate model calls — the
  chosen design strips the markers so the prose is clean and the `citations` frame is the single source of
  truth. The "two calls" alternative was rejected on honesty grounds: two independent turns can diverge,
  so the citations could describe a different answer than the one streamed. The stripping's one subtle
  point (a marker split across a chunk boundary) is called out in the plan's risks.
- **Guardrail / failed-turn parity is mirrored, not re-invented**, following capabilities 20 and 21. The
  new agent is ungoverned by default (capability 12 guards `docs-agent` only), as capability 21's citing
  agent is; a refusal is not a decline; a failed turn is surfaced as an `error` frame, not a 500.
- **Delivered over the service's existing SSE surface style**, reusing capability 20's framing (JSON
  `data:` payloads via the internal mapper, so the wire envelope is a Java-shaped record per the §3/§16
  boundary); no new transport, dependency, or client library is introduced. If a dependency proves
  necessary it must be justified per the project constitution.
- **Correctness offline-deterministic; faithfulness live.** Frame ordering, the honesty-floor
  intersection, the decline rule, and failure framing are proven offline with a mocked model; the
  end-of-stream self-report *faithfulness* finding (SC-006) requires a live run against the local model,
  as prior capabilities do.

## Out of scope

- Changing capabilities 8, 14, 20, or 21 — their endpoints, agents, or response shapes.
- **Retrieval as a tool call inside the stream** — the undocumented pattern this capability deliberately
  sidesteps by retrieving up-front.
- **Showing the inline `[n]` markers in the delivered prose** — the markers are a private server-side
  signal and are stripped; they are never part of the caller's answer (the chosen clean-prose design).
- **A separate non-streamed structured call for the used-sources** (the "two calls" alternative) —
  rejected because two independent model turns can diverge, so the citations would not describe the exact
  answer that was streamed.
- Multi-hop retrieval, re-ranking, or a second corpus.
- Streaming a typed/structured *answer* body — the streamed prose is text; only the terminal frames are
  typed.
