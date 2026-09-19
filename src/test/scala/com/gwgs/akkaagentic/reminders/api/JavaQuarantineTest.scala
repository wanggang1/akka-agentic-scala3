package com.gwgs.akkaagentic.reminders.api

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T034 / FR-013 — the Java quarantine, asserted against the tree rather than promised in prose.
  *
  * Capability 15 contains exactly **one** Java production class, for a measured reason: scheduling a
  * timed action needs a `DeferredCall`, and only a Java method reference produces one (specs/017
  * research Q-A). Cancelling does not (Q-B), so the class that cancels is Scala — which is the point.
  *
  * If this fails, the wall reaches further than capability 15 measured. That is a **finding to record**,
  * not a line to update. (Tests are deliberately out of scope: `BoundedRetryIntegrationTest` is Java for
  * the same measured reason, and a test is not production.)
  */
class JavaQuarantineTest:

  private val javaMain = Path.of("src/main/java/com/gwgs/akkaagentic/reminders")
  private val scalaMain = Path.of("src/main/scala/com/gwgs/akkaagentic/reminders")

  private def filesUnder(root: Path, extension: String): List[String] =
    if !Files.exists(root) then List.empty
    else
      Files.walk(root).iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(_.toString)
        .filter(_.endsWith(extension))
        .toList
        .sorted

  @Test
  def exactlyOneJavaProductionClassExistsAndItSchedules(): Unit =
    assertThat(filesUnder(javaMain, ".java").mkString(", "))
      .isEqualTo("src/main/java/com/gwgs/akkaagentic/reminders/api/ReminderSchedulingEndpoint.java")

  /** The other half of the claim: the wall split this family by OPERATION, not by layer. The class that
    * cancels and reads, the timed action itself, the store and the domain rules are all Scala. */
  @Test
  def cancellingTheActionAndTheRulesAreScala(): Unit =
    val scalaFiles = filesUnder(scalaMain, ".scala")
    for expected <- List(
        "api/ReminderEndpoint.scala",
        "application/ReminderAction.scala",
        "application/ReminderStore.scala",
        "domain/Reminders.scala",
        "domain/ReminderRequest.scala")
    do assertThat(scalaFiles.exists(_.endsWith(expected))).isTrue()
