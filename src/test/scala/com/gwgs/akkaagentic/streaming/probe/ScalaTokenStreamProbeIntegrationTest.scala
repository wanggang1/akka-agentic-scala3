package com.gwgs.akkaagentic.streaming.probe

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.stream.javadsl.Sink
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}
import org.slf4j.LoggerFactory

/** Q-B, the capability's headline question: can a **Scala** caller consume an agent's token stream?
  *
  * The prediction was no. Two of the four attempts are already settled at **compile** time and are
  * recorded as deleted files in specs/016 research rather than kept as dead code:
  *   - `dynamicCall(id).source(msg)` — *"value source is not a member of DynamicMethodRef"*. The
  *     project's one escape hatch has no streaming counterpart.
  *   - `tokenStream("streaming-chat-agent")` — no overload takes a `String`. There is no string-keyed
  *     token stream anywhere in the client API (jar sweep).
  *
  * What remains is the documented form written as a Scala lambda. It **compiles** — `akka.japi.function
  * .Function2` is a SAM, so scalac converts a lambda to it happily — which means the question is not
  * "can it be written" but "does `MethodRefResolver` resolve it at run time". A Scala lambda compiles
  * to a synthetic `$anonfun`, and capability 13 measured the analogous case failing at run time with a
  * diagnostic that blames the caller's own class. This probe records which it is, in fact.
  */
class ScalaTokenStreamProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(classOf[ScalaTokenStreamProbeIntegrationTest])

  private val model = new TestModelProvider()

  private val Answer =
    "The runtime persists the task and the agent's process state as the loop runs. " +
      "That is why work survives a restart without any persistence code of your own."

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[StreamingChatAgent], model)

  @BeforeEach
  def reset(): Unit =
    model.reset()
    model.fixedResponse(Answer)

  @Test
  def recordWhetherAScalaCallerCanConsumeATokenStream(): Unit =
    val outcome = Try {
      val source = componentClient
        .forAgent()
        .inSession(UUID.randomUUID().toString)
        // The documented Java form, as a Scala lambda. Compiles; the question is runtime resolution.
        .tokenStream[StreamingChatAgent, String]((agent, message) => agent.stream(message))
        .source("why does work survive a restart?")

      source
        .runWith(Sink.seq[String], testKit.getMaterializer())
        .toCompletableFuture
        .get(30, TimeUnit.SECONDS)
        .asScala
        .toList
    }

    val verdict = outcome match
      case Success(tokens) =>
        s"SCALA-CLEAN: the lambda resolved and the stream delivered ${tokens.size} token(s); " +
          s"concatenation ${if tokens.mkString == Answer then "EQUALS" else "DIFFERS FROM"} the scripted answer"
      case Failure(t) =>
        val chain = Iterator
          .iterate(t: Throwable)(_.getCause)
          .takeWhile(_ != null)
          .map(e => s"${e.getClass.getName}: ${e.getMessage}")
          .mkString(" <- ")
        s"WALL: $chain"

    logger.info("Q-B probe >>> {}", verdict)

    // Recorded, not demanded: the probe exists to find out which of the two it is. Whatever it shows
    // decides the capability's shape (FR-013), and goes into research.md verbatim.
    assertThat(verdict).isNotEmpty()
