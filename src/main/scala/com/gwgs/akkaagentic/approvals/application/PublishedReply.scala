package com.gwgs.akkaagentic.approvals.application

import akka.javasdk.annotations.Description
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** The typed result of the publish task — the terminal output of an approved case.
  *
  * A **component payload** produced by [[PublishAgent]] through the built-in `complete_task` tool, so it
  * stays Java-shaped for the SDK's internal mapper (research R5), like [[Draft]].
  *
  * Its mere existence is the capability's core assertion: a `PublishedReply` can only ever exist for a
  * case whose human gate was approved, because the publish task depends on the approval task (FR-007).
  */
final case class PublishedReply @JsonCreator() (
    @JsonProperty("reply")
    @Description("The final, approved reply published to the customer.")
    reply: String
)
