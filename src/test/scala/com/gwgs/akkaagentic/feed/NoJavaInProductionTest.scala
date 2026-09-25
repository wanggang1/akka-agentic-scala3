package com.gwgs.akkaagentic.feed

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T037 / FR-013 — the inverse of capabilities 14 and 15's quarantine pins.
  *
  * Those asserted "exactly one Java class, and here is the measured reason". This capability asserts
  * **none**: the Consumer family holds no method reference anywhere, so neither the component nor its
  * reader is forced across the language boundary (specs/018 research Q-A/Q-C).
  *
  * If this ever fails, the wall reached this family after all — a **finding to record**, not a line to
  * relax. (Tests are deliberately out of scope: `BoundedActivityDeliveryIntegrationTest` is Java because
  * writing capability 6's entity needs a method reference, and a test is not production.)
  */
class NoJavaInProductionTest:

  @Test
  def theCapabilityHasNoJavaProductionSources(): Unit =
    val javaMain = Path.of("src/main/java/com/gwgs/akkaagentic/feed")
    val found =
      if !Files.exists(javaMain) then Nil
      else
        Files.walk(javaMain).iterator.asScala
          .filter(Files.isRegularFile(_))
          .map(_.toString)
          .filter(_.endsWith(".java"))
          .toList
          .sorted
    assertThat(found.mkString(", ")).isEmpty()

  @Test
  def theComponentAndItsReaderAreBothScala(): Unit =
    val scalaMain = Path.of("src/main/scala/com/gwgs/akkaagentic/feed")
    val files =
      Files.walk(scalaMain).iterator.asScala.filter(Files.isRegularFile(_)).map(_.toString).toList
    for expected <- List(
        "application/TodoActivityConsumer.scala", // the component
        "api/TodoActivityEndpoint.scala",         // its reader — the part capability 11's View needed Java for
        "application/BoundedDelivery.scala",
        "domain/TodoDiff.scala")
    do assertThat(files.exists(_.endsWith(expected))).isTrue()
