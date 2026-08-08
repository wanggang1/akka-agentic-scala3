package com.gwgs.akkaagentic.activities.application

import akka.javasdk.annotations.Description
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** The typed result of a `SUGGEST` task — what the coordinator produces when it completes.
  *
  * A **component payload** delivered via the built-in `complete_task` tool: `resultConformsTo` uses
  * this class both to generate that tool's JSON schema and to deserialize the model's completion,
  * through the SDK's *internal* Jackson mapper (which the feature-003 Scala hook does NOT reach). So
  * it stays **Java-shaped** — explicit `@JsonCreator`/`@JsonProperty`, and a `java.util.List` (not a
  * Scala `List`). Mirrors cap-3's `HelpAnswer`. See specs/009 research D3.
  *
  * The `@Description`s flow into the generated schema, so the model sees what each field means.
  */
final case class ActivitySuggestion @JsonCreator() (
    @JsonProperty("suggestion")
    @Description("The recommended activities, one or more sentences, suited to the weather and preferences.")
    suggestion: String,
    @JsonProperty("weatherConsidered")
    @Description("The weather conditions the suggestion took into account; empty if none.")
    weatherConsidered: String,
    @JsonProperty("consultedSpecialists")
    @Description(
      "The specialist agents consulted while producing this suggestion (e.g. weather-specialist, " +
        "activity-specialist); empty if none."
    )
    consultedSpecialists: java.util.List[String]
)
