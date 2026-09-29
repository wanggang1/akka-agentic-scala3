package com.gwgs.akkaagentic.compaction

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T035 / FR-013 — the Java quarantine is **exactly one class**, and stays that way.
  *
  * Capabilities 14 and 15 pin their quarantine by file count so that growth becomes a recorded finding
  * rather than silent drift. This capability pins the same thing, and one more: that the single Java class
  * holds **no logic**.
  *
  * That second half was not planned — javac refused the first draft with *"enum classes may not be
  * instantiated"*, because a Java caller cannot construct a Scala 3 `enum` case (finding I-1). Rather than
  * work around it with Scala-side factories, the mapping and the outcome decision moved to `Compactor`,
  * and the gateway became three thin operations that decide nothing. So the quarantine here is one class
  * **and** one responsibility, which is a stronger claim than a file count alone.
  */
class OneJavaClassTest:

  private val productionJava = Path.of("src/main/java/com/gwgs/akkaagentic/compaction")

  private def javaFiles(root: Path, includeProbe: Boolean): List[String] =
    if !Files.exists(root) then Nil
    else
      Files
        .walk(root)
        .iterator
        .asScala
        .filter(Files.isRegularFile(_))
        .map(_.toString)
        .filter(_.endsWith(".java"))
        .filter(p => includeProbe || !p.contains("/probe/"))
        .toList
        .sorted

  @Test
  def exactlyOneJavaClassInProduction(): Unit =
    val found = javaFiles(productionJava, includeProbe = false)
    assertThat(found.mkString(", "))
      .isEqualTo("src/main/java/com/gwgs/akkaagentic/compaction/application/SessionMemoryGateway.java")

  /** The measured reason it exists, so a future reader does not "simplify" it into Scala and discover the
    * three method references the hard way. */
  @Test
  def itIsTheOnlyPlaceAMethodReferenceLives(): Unit =
    val gateway =
      Files.readString(
        Path.of("src/main/java/com/gwgs/akkaagentic/compaction/application/SessionMemoryGateway.java"))
    assertThat(gateway).contains("SessionMemoryEntity::getHistory")
    assertThat(gateway).contains("SessionMemoryEntity::compactHistory")
    assertThat(gateway).contains("CompactionAgent::summarize")
    assertThat(gateway).contains("withDetailedReply")

    // And no Scala source holds one — every attempt would compile and then fail at run time.
    val scalaSources =
      Files
        .walk(Path.of("src/main/scala/com/gwgs/akkaagentic/compaction"))
        .iterator
        .asScala
        .filter(Files.isRegularFile(_))
        .filter(_.toString.endsWith(".scala"))
        .map(Files.readString)
        .toList
    assertThat(scalaSources.exists(_.contains("SessionMemoryEntity::"))).isFalse()

  /** The quarantine holds no decisions: the outcome enum and the message mapping live in Scala. */
  @Test
  def theJavaClassHoldsNoLogic(): Unit =
    val gateway =
      Files.readString(
        Path.of("src/main/java/com/gwgs/akkaagentic/compaction/application/SessionMemoryGateway.java"))
    assertThat(gateway).doesNotContain("Outcome")
    assertThat(gateway).doesNotContain("HistoryLine")
    assertThat(gateway).doesNotContain("SummaryRequest")
