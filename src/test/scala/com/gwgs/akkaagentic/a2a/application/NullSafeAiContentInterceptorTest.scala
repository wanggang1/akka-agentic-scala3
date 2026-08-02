package com.gwgs.akkaagentic.a2a.application

import akka.javasdk.agent.SessionMessage.{AiMessage, ToolCallRequest}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import java.time.Instant
import java.util.{List => JList}

/** Pure unit test for [[NullSafeAiContentInterceptor]] — no runtime, no model.
  *
  * The live NPE (a null-content tool-call turn re-thrown on replay) cannot be reproduced offline: the
  * mock persists the null-content message but never replays stored history through the model-provider
  * conversion where the throw fires (feature 006 research R6, re-confirmed feature 008). So the fix is
  * verified at its unit — `beforeWrite` normalizing null `text` to "" while preserving the tool call.
  */
class NullSafeAiContentInterceptorTest:

  @Test
  def normalizesNullTextToEmptyPreservingToolCalls(): Unit =
    val toolCalls = JList.of(new ToolCallRequest("id-1", "TodoTools_addTodo", """{"description":"x"}"""))
    val nullContent = new AiMessage(Instant.now(), null, "personal-assistant-agent", toolCalls)

    val result = NullSafeAiContentInterceptor.beforeWrite("alice", nullContent)

    assertThat(result.text()).isEqualTo("") // null -> "", the replay-safe form
    assertThat(result.toolCallRequests()).isEqualTo(toolCalls) // in-flight tool call preserved

  @Test
  def leavesNonNullTextUntouched(): Unit =
    val ok = new AiMessage(Instant.now(), "all good", "personal-assistant-agent")

    val result = NullSafeAiContentInterceptor.beforeWrite("alice", ok)

    assertThat(result).isSameAs(ok) // common case: identity, no needless copy
