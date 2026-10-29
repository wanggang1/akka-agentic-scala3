package com.gwgs.akkaagentic.docs.api

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T020 (cap-22) — the Java quarantine for the `docs` tree, asserted against the tree rather than promised
  * in prose.
  *
  * The `docs` tree was **entirely Scala** through cap-8 (`/ask`) and cap-21 (`/cited-ask`) — agents,
  * endpoints, domain, all of it. Capability 22 (fork B5, `POST /sse-cited-ask`) introduces the **first two**
  * Java files into the tree, each for a measured reason and the same two as cap-14/20's `streaming` tree:
  *   - `StreamingCitedDocsEndpoint` — consuming an agent's token stream needs a Java method reference
  *     (`tokenStream(StreamingCitedDocsAgent::stream)`; README §16, specs/016 Q-B). The whole SSE pipeline
  *     (strip → concatLazy citations → recover → serverSentEvents) lives in this one class.
  *   - `GroundedSseEvent` — the SSE wire envelope, Java because the SDK's internal Jackson mapper serialises
  *     each frame (specs/003 `scala-jackson-module-followup`).
  *
  * Everything the endpoint *calls* — the streaming agent, the stripper, the retrieval/floor façade — is
  * Scala, so the wall claims only these two `api` classes and travels no further. A prose claim like that
  * decays the first time someone adds a Java helper "just here"; so it is a test. If the Java set changes,
  * that is a **finding to record** (the wall reached further, or a new wire type appeared), not a line to
  * quietly update.
  */
class JavaQuarantineTest:

  private val javaMain = Path.of("src/main/java/com/gwgs/akkaagentic/docs")
  private val scalaMain = Path.of("src/main/scala/com/gwgs/akkaagentic/docs")

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
  def exactlyTheTwoKnownJavaProductionClassesExist(): Unit =
    assertThat(filesUnder(javaMain, ".java").mkString(", "))
      .isEqualTo(
        List(
          "src/main/java/com/gwgs/akkaagentic/docs/api/GroundedSseEvent.java",
          "src/main/java/com/gwgs/akkaagentic/docs/api/StreamingCitedDocsEndpoint.java"
        ).mkString(", ")
      )

  /** The other half of the claim: the quarantine is two `api` classes, not whole layers. The streaming
    * agent and the capability's new domain/application collaborators are all Scala, so the wall did not
    * spread from the endpoint to what it calls. */
  @Test
  def theAgentStripperAndFacadeAreScala(): Unit =
    val scalaFiles = filesUnder(scalaMain, ".scala")

    assertThat(scalaFiles.exists(_.endsWith("application/StreamingCitedDocsAgent.scala"))).isTrue()
    assertThat(scalaFiles.exists(_.endsWith("application/GroundedCitedPrep.scala"))).isTrue()
    assertThat(scalaFiles.exists(_.endsWith("domain/CitationMarkers.scala"))).isTrue()
