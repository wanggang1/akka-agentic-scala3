package com.gwgs.akkaagentic.compaction.application

import akka.javasdk.annotations.Description
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** What a summariser produces: one user message and one AI message that stand in for many turns.
  *
  * This is a **component payload** — it is the summariser agent's structured result, so it crosses the
  * SDK's *internal* Jackson mapper, which the feature-003 `Bootstrap`/`DefaultScalaModule` hook does not
  * reach (the two-mapper finding, README §3). So it stays **Java-shaped**: explicit
  * `@JsonCreator`/`@JsonProperty` and plain fields, exactly like cap-3's `HelpAnswer`. The `@Description`s
  * are not cosmetic — they reach the model through the generated schema.
  *
  * It lives in `application` rather than `domain` on purpose, and the same reason puts cap-3's
  * `HelpAnswer` there: the `@Description`s are an Akka annotation, and the constitution's Principle II
  * keeps `domain` free of Akka. A result type carrying schema hints for a model is an application concern.
  *
  * A blank half is a **failed** compaction, not a very small one: replacing a conversation with nothing
  * would satisfy the byte bound and destroy the point. [[isUsable]] is the guard, and the caller treats a
  * `false` exactly as it treats a summariser that threw.
  */
final case class ConversationSummary @JsonCreator() (
    @JsonProperty("userMessage")
    @Description("A single user message summarising what the user said and asked across the conversation.")
    userMessage: String,
    @JsonProperty("aiMessage")
    @Description(
      "A single assistant message summarising what was answered and what any tools established, as prose."
    )
    aiMessage: String
):
  def isUsable: Boolean =
    userMessage != null && aiMessage != null && userMessage.trim.nonEmpty && aiMessage.trim.nonEmpty
