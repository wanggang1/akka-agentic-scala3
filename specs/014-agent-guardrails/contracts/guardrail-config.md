# Contract: guardrail declaration (cap-12)

**Feature**: `014-agent-guardrails` | **File**: `src/main/resources/application.conf`

Guardrails are attached by configuration only. **No component descriptor entry** (research R5), and
**no reference to any rule inside the guarded agent** (FR-006, SC-006).

```conf
akka.javasdk.agent.guardrails {

  # (1) Enable the SDK's OWN pre-declared jailbreak rule for this agent. The SDK's reference.conf
  #     already defines class/category/use-for/threshold/bad-examples; it ships disabled because
  #     `agents = []`. Overriding that one key is the whole enablement (research R2).
  "default jailbreak".agents = ["docs-agent"]

  # (2) Enforcing, response side, settings-taking constructor.
  "linked answer guard" {
    class        = "com.gwgs.akkaagentic.docs.application.LinkedAnswerGuard"
    agents       = ["docs-agent"]
    category     = HALLUCINATED
    use-for      = ["model-response"]
    report-only  = false
    link-markers = ["http://", "https://", "www."]   # read via GuardrailContext.config
  }

  # (3) Record-only, response side, NO-ARG constructor (the loader's second attempt).
  "answer length guard" {
    class       = "com.gwgs.akkaagentic.docs.application.AnswerLengthGuard"
    agents      = ["docs-agent"]
    category    = FORMAT
    use-for     = ["model-response"]
    report-only = true
  }
}
```

## Required keys per rule

| Key | Required | Notes |
|---|---|---|
| `class` | yes | must be **public** with a **public constructor**, optionally `(GuardrailContext)` |
| `category` | yes | free text; JAILBREAK / PROMPT_INJECTION / PII / TOXIC / HALLUCINATED / NSFW / FORMAT are the recommended set |
| `use-for` | yes | `model-request`, `model-response`, `mcp-tool-request`, `mcp-tool-response`, or `*` |
| `report-only` | yes | `false` aborts the interaction, `true` records and continues |
| `agents` and/or `agent-roles` | at least one | `"*"` in `agents` covers every agent |
| *anything else* | no | delivered to the rule as its `GuardrailContext.config` section |

## Behavioural contract

- **Enforcing ↔ record-only is a config-only switch** — flipping `report-only` changes the
  caller-visible outcome with zero code change (SC-005), verified by a test that overrides it via
  `TestKit.Settings.withAdditionalConfig`.
- **A rule that cannot be loaded fails the service at startup**, rather than leaving the agent
  unguarded (FR-010, research R4).
- **A rule not naming an agent never fires for it** (US4).
