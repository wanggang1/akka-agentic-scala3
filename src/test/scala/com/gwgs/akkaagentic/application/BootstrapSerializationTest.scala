package com.gwgs.akkaagentic.application

import akka.javasdk.JsonSupport
import akka.javasdk.testkit.{TestKit, TestKitSupport}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A plain, annotation-free Scala case class with `Option` fields — the thing that only
  * (de)serializes once [[Bootstrap]] has registered `DefaultScalaModule`. Defined at the
  * top level on purpose: Jackson cannot construct a non-static (nested) inner class.
  */
final case class Sample(user: Option[String], text: Option[String], count: Int)

/** Proves the foundational change (T002/T003) actually works: extending [[TestKitSupport]]
  * boots the service, which runs `Bootstrap.onStartup`, which registers the Scala module on
  * the SDK's shared `ObjectMapper`. If the `service-setup` descriptor entry were wrong, the
  * module would not be registered and these round-trips would fail.
  */
class BootstrapSerializationTest extends TestKitSupport:

  // The agent component is loaded at startup; give it a dummy key so boot succeeds offline.
  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")

  @Test
  def presentAndNoneRoundTrip(): Unit =
    val mapper = JsonSupport.getObjectMapper()
    val original = Sample(Some("Ada"), None, 3)
    val back = mapper.readValue(mapper.writeValueAsString(original), classOf[Sample])
    assertThat(back).isEqualTo(original)
    assertThat(back.user).isEqualTo(Some("Ada"))
    assertThat(back.text).isEqualTo(None)

  @Test
  def absentPropertyBecomesNone(): Unit =
    val mapper = JsonSupport.getObjectMapper()
    val back = mapper.readValue("""{"count":5}""", classOf[Sample])
    assertThat(back).isEqualTo(Sample(None, None, 5))

  @Test
  def explicitNullBecomesNone(): Unit =
    val mapper = JsonSupport.getObjectMapper()
    val back = mapper.readValue("""{"user":null,"text":null,"count":5}""", classOf[Sample])
    assertThat(back).isEqualTo(Sample(None, None, 5))
