package com.gwgs.akkaagentic.approvals.application

import akka.javasdk.annotations.Description
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** The typed result of the approval task — the human's decision that releases the gate.
  *
  * Unlike [[Draft]] and [[PublishedReply]], **no agent produces this**: the approval task is the
  * unassigned gate, and the endpoint completes it on the reviewer's behalf
  * (`forTask(approvalId).complete(ApprovalTasks.APPROVAL, ApprovalDecision(true, note))`). It still
  * crosses the SDK's internal mapper as a task result, so it stays Java-shaped (research R5).
  *
  * Exists only for the **approved** path: a rejection does not complete the task with a decision, it
  * *fails* the task with the reviewer's note as the failure reason, which auto-cancels the dependent
  * publish task (research R3). Hence `approved` is always `true` here.
  */
final case class ApprovalDecision @JsonCreator() (
    @JsonProperty("approved")
    @Description("Whether the reviewer approved the draft; always true on the completion path.")
    approved: Boolean,
    @JsonProperty("note")
    @Description("Optional reviewer comment recorded with the approval; empty when none was given.")
    note: String
)
