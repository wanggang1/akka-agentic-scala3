package com.gwgs.akkaagentic.feed.api

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList
import com.gwgs.akkaagentic.feed.application.{ActivityStore, TodoActivityConsumer}
import com.gwgs.akkaagentic.feed.probe.ProbeLog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}
import org.slf4j.LoggerFactory

/** T026 — User Story 4: each recorded change is published, and nothing else is.
  *
  * "Consume" is half of this component family; **this is the other half**, and the half nothing in the
  * project had tested before. What is asserted here is the part that could silently be wrong: that a
  * duplicate publishes nothing, that a set-aside publishes nothing, and that the payload really does
  * serialize idiomatically (research Q-C measured that a produced message goes through the Scala-aware
  * mapper — unlike a component payload, which would fail on an `Option`).
  */
class TodoActivityPublishIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(classOf[TodoActivityPublishIntegrationTest])

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withKeyValueEntityIncomingMessages(classOf[TodoEntity])
      .withTopicOutgoingMessages(TodoActivityConsumer.Topic)

  private def incoming = testKit.getKeyValueEntityIncomingMessages(classOf[TodoEntity])
  private def published = testKit.getTopicOutgoingMessages(TodoActivityConsumer.Topic)

  @BeforeEach
  def reset(): Unit =
    ActivityStore.clear()
    ProbeLog.clear()
    published.clear()

  private def list(items: (String, Boolean)*): TodoList =
    items.foldLeft(TodoList.empty())((l, item) => l.add(item._1)) match
      case built =>
        items.zipWithIndex.foldLeft(built) { case (l, ((_, completed), i)) =>
          if completed then l.setCompleted(i + 1, true) else l
        }

  @Test
  def eachRecordedChangeIsPublishedWithTheUserAsItsSubject(): Unit =
    incoming.publish(list(("buy milk", false)), "gina")
    val message = published.expectOneRaw(Duration.ofSeconds(10))
    val payload = message.getPayload.toString(UTF_8)
    val metadata = message.getMetadata.asScala.map(e => e.getKey -> e.getValue).toMap
    logger.info("US4 >>> published: {}", payload)
    logger.info("US4 >>> ce-subject={} ce-type={}", metadata.getOrElse("ce-subject", "-"), metadata.getOrElse("ce-type", "-"))

    assertThat(payload).contains("\"username\":\"gina\"")
    assertThat(payload).contains("\"kind\":\"baseline\"")
    // A field that does not apply to this kind is OMITTED, not sent as null: `None` would otherwise be
    // written as null (measured on the first run), leaving a subscriber to interpret it.
    assertThat(payload).doesNotContain("itemId")
    assertThat(payload).doesNotContain("null")
    // ce-subject is what keeps one user's messages in order on a real broker (the SDK docs require it).
    assertThat(metadata.getOrElse("ce-subject", "")).isEqualTo("gina")

  @Test
  def thePayloadSerializesIdiomaticallyWithOptionalFieldsAsPlainValues(): Unit =
    incoming.publish(list(("buy milk", false)), "hank")
    published.expectOneRaw(Duration.ofSeconds(10))                       // the baseline
    incoming.publish(list(("buy milk", false), ("call mum", false)), "hank")
    val payload = published.expectOneRaw(Duration.ofSeconds(10)).getPayload.toString(UTF_8)
    logger.info("US4 >>> published change: {}", payload)

    // `itemId`/`description` are Options in the Scala payload: present here as plain values, not as a
    // wrapper object and not as null — which is the measured difference between the Scala-aware mapper
    // and the internal one (README §3).
    assertThat(payload).contains("\"kind\":\"added\"")
    assertThat(payload).contains("\"itemId\":2")
    assertThat(payload).contains("\"description\":\"call mum\"")

  @Test
  def aDuplicateDeliveryPublishesNothing(): Unit =
    val state = list(("buy milk", false))
    incoming.publish(state, "ivan")
    published.expectOneRaw(Duration.ofSeconds(10))
    incoming.publish(state, "ivan")
    // Nothing recorded, so nothing published — asserted by waiting, not by counting immediately.
    published.expectNone(Duration.ofSeconds(3))

  @Test
  def aSetAsideIsNeverPublishedAsActivity(): Unit =
    // The probe consumer is the one that fails; it publishes nothing at all, and the production consumer
    // publishes only what it recorded. So a poisoned user produces set-asides and no activity message
    // beyond the change the production consumer legitimately recorded.
    ProbeLog.poison("jane")
    incoming.publish(list(("doomed", false)), "jane")
    val payload = published.expectOneRaw(Duration.ofSeconds(10)).getPayload.toString(UTF_8)
    assertThat(payload).contains("\"username\":\"jane\"")
    published.expectNone(Duration.ofSeconds(3))
    assertThat(payload).doesNotContain("set-aside")

  @Test
  def aDeletedListIsPublishedToo(): Unit =
    incoming.publish(list(("buy milk", false)), "karl")
    published.expectOneRaw(Duration.ofSeconds(10))
    incoming.publishDelete("karl")
    val payload = published.expectOneRaw(Duration.ofSeconds(10)).getPayload.toString(UTF_8)
    assertThat(payload).contains("\"kind\":\"list-deleted\"")
