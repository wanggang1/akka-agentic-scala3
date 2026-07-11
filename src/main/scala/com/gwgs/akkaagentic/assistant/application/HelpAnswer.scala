package com.gwgs.akkaagentic.assistant.application

import akka.javasdk.annotations.Description
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** The typed result of a help-desk task — what the agent produces when it completes.
  *
  * This is a **component payload**: the Autonomous Agent delivers it via the built-in
  * `complete_task` tool, and `Task.resultConformsTo(classOf[HelpAnswer])` uses this class both to
  * generate that tool's JSON schema and to deserialize the model's tool arguments. Both go through
  * the SDK's *internal* Jackson mapper, which the feature-003 `Bootstrap`/`DefaultScalaModule` hook
  * does **not** reach (the two-mapper finding). So it must stay **Java-shaped**: explicit
  * `@JsonCreator`/`@JsonProperty`, and a `java.util.List` (not a Scala `List`) the annotation-free
  * mapper can construct. Mirrors cap-1's `GreetingAgent.Result`. See specs/005 research R3.
  *
  * The `@Description`s are not cosmetic here: they flow into the generated `complete_task` schema, so
  * the model sees what each field means.
  */
final case class HelpAnswer @JsonCreator() (
    @JsonProperty("answer")
    @Description("The answer to the user's question, one or more sentences.")
    answer: String,
    @JsonProperty("category")
    @Description("A short classification of the question, e.g. account, billing, shipping, general.")
    category: String,
    @JsonProperty("citedTopics")
    @Description(
      "Knowledge-base topics consulted while answering; empty when none were used."
    )
    citedTopics: java.util.List[String],
    @JsonProperty("confidence")
    @Description("Self-reported confidence in the answer, 0-100.")
    confidence: Int
)
