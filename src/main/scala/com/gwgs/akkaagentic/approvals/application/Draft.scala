package com.gwgs.akkaagentic.approvals.application

import akka.javasdk.annotations.Description
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** The typed result of the draft task — the candidate reply the reviewer sees at the gate.
  *
  * A **component payload**: [[DraftAgent]] delivers it through the built-in `complete_task` tool, and
  * `Task.resultConformsTo(classOf[Draft])` uses this class both to generate that tool's JSON schema and
  * to deserialize the model's tool arguments. Both go through the SDK's *internal* Jackson mapper, which
  * the feature-003 `Bootstrap`/`DefaultScalaModule` hook does **not** reach (the two-mapper finding), so
  * it stays **Java-shaped**: explicit `@JsonCreator`/`@JsonProperty`, no `Option`. Mirrors cap-3's
  * `HelpAnswer`. See specs/007 research R5.
  *
  * The `@Description` flows into the generated schema, so the model sees what the field means.
  */
final case class Draft @JsonCreator() (
    @JsonProperty("body")
    @Description("The candidate reply drafted for the customer, ready for human review.")
    body: String
)
