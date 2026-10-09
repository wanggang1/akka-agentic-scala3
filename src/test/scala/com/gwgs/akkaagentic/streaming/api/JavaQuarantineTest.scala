package com.gwgs.akkaagentic.streaming.api

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T012 / FR-013 (cap-14) + T015 (cap-20) — the Java quarantine for the whole `streaming` tree,
  * asserted against the tree rather than promised in prose.
  *
  * The tree now hosts **two** capabilities, and each adds Java only for a measured reason:
  *   - cap-14: `StreamingChatEndpoint` — consuming an agent's token stream needs a Java method
  *     reference (specs/016 research Q-B).
  *   - cap-20: `SseChatEndpoint` — the SAME wall (it also holds `tokenStream(StreamingChatAgent::stream)`),
  *     and `SseChatEvent` — the SSE wire envelope, Java because the SDK's internal Jackson mapper
  *     serialises each frame (specs/022 research Q-D).
  *
  * So the quarantine is now **three** Java files, all in `api`. Everything either capability *calls* —
  * the agent, both domain rules — is Scala, so neither wall spread from an endpoint to its collaborators.
  *
  * A prose claim like that decays the first time someone adds a Java helper "just here". So it is a
  * test. If the Java set changes, that is a **finding to record** (the wall reached further, or a new
  * wire type appeared), not a line to quietly update.
  */
class JavaQuarantineTest:

  private val javaMain = Path.of("src/main/java/com/gwgs/akkaagentic/streaming")
  private val scalaMain = Path.of("src/main/scala/com/gwgs/akkaagentic/streaming")

  private def filesUnder(root: Path, extension: String): List[String] =
    if !Files.exists(root) then List.empty
    else
      Files
        .walk(root)
        .iterator
        .asScala
        .filter(Files.isRegularFile(_))
        .map(_.toString)
        .filter(_.endsWith(extension))
        .toList
        .sorted

  @Test
  def exactlyTheThreeKnownJavaProductionClassesExist(): Unit =
    assertThat(filesUnder(javaMain, ".java").mkString(", "))
      .isEqualTo(
        List(
          "src/main/java/com/gwgs/akkaagentic/streaming/api/SseChatEndpoint.java",
          "src/main/java/com/gwgs/akkaagentic/streaming/api/SseChatEvent.java",
          "src/main/java/com/gwgs/akkaagentic/streaming/api/StreamingChatEndpoint.java"
        ).mkString(", ")
      )

  /** The other half of the claim: the quarantine is a handful of *api* classes, not whole *layers*.
    * Both agents' collaborators — the agent and both domain rules — are Scala, so neither wall spread
    * from an endpoint to what it calls. */
  @Test
  def theAgentAndBothDomainRulesAreScala(): Unit =
    val scalaFiles = filesUnder(scalaMain, ".scala")

    assertThat(scalaFiles.exists(_.endsWith("application/StreamingChatAgent.scala"))).isTrue()
    assertThat(scalaFiles.exists(_.endsWith("domain/StreamQuestion.scala"))).isTrue()
    assertThat(scalaFiles.exists(_.endsWith("domain/SseErrorReason.scala"))).isTrue()
