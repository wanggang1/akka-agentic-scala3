package com.gwgs.akkaagentic.approvals.application

import akka.javasdk.agent.task.Task

/** The three task types that make up an approval case: draft → human gate → publish.
  *
  * Task definitions are plain constants (no Akka component, so they are **not** listed in the component
  * descriptor). `resultConformsTo` binds each typed result; per-case context is added at call time with
  * `.instructions(...)`, and the ordering is wired with `.dependsOn(...)`.
  *
  * The gate is [[APPROVAL]]: unlike the other two it is assigned to **no agent**, so once its dependency
  * (the draft) completes it simply sits at `PENDING` until a human completes it (approve) or fails it
  * (reject). That is the whole external-input pattern — the gate is realized by the dependency graph,
  * not by any ordering in our code (specs/007 research R3, FR-007).
  */
object ApprovalTasks:

  val DRAFT: Task[Draft] =
    Task
      .name("Draft")
      .description("Draft a candidate customer reply for the given question, for human review.")
      .resultConformsTo(classOf[Draft])

  val APPROVAL: Task[ApprovalDecision] =
    Task
      .name("Approval")
      .description("Human approval gate for the drafted reply; completed to approve, failed to reject.")
      .resultConformsTo(classOf[ApprovalDecision])

  val PUBLISH: Task[PublishedReply] =
    Task
      .name("Publish")
      .description("Publish the approved reply to the customer.")
      .resultConformsTo(classOf[PublishedReply])
