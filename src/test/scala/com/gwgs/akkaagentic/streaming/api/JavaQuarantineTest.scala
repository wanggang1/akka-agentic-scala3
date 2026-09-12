package com.gwgs.akkaagentic.streaming.api

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T012 / FR-013 — the Java quarantine, asserted against the tree rather than promised in prose.
  *
  * Capability 14 contains exactly **one** Java production class, and for a measured reason: consuming
  * an agent's token stream needs a Java method reference (specs/016 research Q-B). Everything else —
  * the agent, the domain rule, this capability's own tests — is Scala.
  *
  * A prose claim like that decays the first time someone finds it easier to add a second Java helper.
  * So it is a test. If it fails, the wall reaches further than capability 11 measured, which is a
  * **finding to record**, not a line to update.
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
  def exactlyOneJavaProductionClassExistsAndItIsTheEndpoint(): Unit =
    assertThat(filesUnder(javaMain, ".java").mkString(", "))
      .isEqualTo("src/main/java/com/gwgs/akkaagentic/streaming/api/StreamingChatEndpoint.java")

  /** The other half of the claim: the quarantine is one *class*, not one *layer*. The agent and the
    * domain rule are Scala, so the wall did not spread from the endpoint to what it calls. */
  @Test
  def theAgentAndTheDomainRuleAreScala(): Unit =
    val scalaFiles = filesUnder(scalaMain, ".scala")

    assertThat(scalaFiles.exists(_.endsWith("application/StreamingChatAgent.scala"))).isTrue()
    assertThat(scalaFiles.exists(_.endsWith("domain/StreamQuestion.scala"))).isTrue()
