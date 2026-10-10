# Quickstart: Usage-accurate citations (`POST /cited-ask`)

New surface alongside cap-8's `/ask`. Same corpus, same retrieval; the difference is that citations
reflect what the model *used*, not everything that was *retrieved*.

## Build & test

```bash
# unit — pure citation-selection logic (fast)
mvn test -Dtest='UsageCitationsTest'

# integration — the new endpoint, model mocked (deterministic)
mvn verify -Dit.test='CitedDocsEndpointIntegrationTest' -Dtest='!*' -DfailIfNoTests=false

# full gate before "done" / PR
mvn clean verify
```

## Run locally (live model via Ollama)

```bash
# needs Ollama running with qwen3:8b pulled
mvn compile exec:java
```

### In-corpus question — usage-accurate citation

```bash
curl -sS localhost:9000/cited-ask -H 'content-type: application/json' \
  -d '{"question":"what makes agent work survive a restart?"}' | jq
# → answer grounded in the corpus
#   citedSources:     ["durability-tasks"]                 ← only what the answer used
#   retrievedSources: ["durability-tasks","cap-3-help-desk","cap-4-session-memory"]
# Compare with cap-8, which cites all three:
curl -sS localhost:9000/ask -H 'content-type: application/json' \
  -d '{"question":"what makes agent work survive a restart?"}' | jq .citedSources
```

### Out-of-corpus question — honest decline, cites nothing

```bash
curl -sS localhost:9000/cited-ask -H 'content-type: application/json' \
  -d '{"question":"what is the capital of France?"}' | jq
# → {"answer":"I don't know","citedSources":[],"retrievedSources":[...]}
```

### Invalid input — 400, no model call

```bash
curl -sS -o /dev/null -w '%{http_code}\n' localhost:9000/cited-ask \
  -H 'content-type: application/json' -d '{"question":"   "}'
# → 400
```

## The measurement (what B4 is really for)

Run several in-corpus questions live and record, per question: `retrievedSources`, the model's reported
`usedSources`, the resulting `citedSources`, and whether the narrowing is trustworthy (no hallucinated
label — guaranteed; no genuinely-used source omitted — judged by reading the answer). This goes in the
README live Q-H block and is summarised into `research.md` R6. It is the evidence for the reopened
cap-7 D6 self-report tension — stated from measurement, not assumed.
