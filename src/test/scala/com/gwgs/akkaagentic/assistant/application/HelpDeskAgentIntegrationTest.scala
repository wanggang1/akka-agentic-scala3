package com.gwgs.akkaagentic.assistant.application

import java.time.Duration
import java.util.UUID

import akka.javasdk.agent.task.TaskStatus
import akka.javasdk.testkit.TestModelProvider.AutonomousAgentTools.completeTask
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[HelpDeskAgent]] with a mocked model (no live model).
  *
  * This is the first place the Autonomous Agent actually runs, so it verifies two research findings:
  *   - **R3** — `HelpAnswer` (a Java-shaped Scala case class) round-trips through the SDK's internal
  *     mapper: the mocked `complete_task` result is serialized and read back as a typed `HelpAnswer`.
  *   - **R4** — the typed result is delivered via the `complete_task` *tool* (function calling), and
  *     the domain `lookupPolicy` tool is also function calling; no JSON response mime type is involved.
  */
class HelpDeskAgentIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[HelpDeskAgent], model)

  @BeforeEach
  def resetModel(): Unit = model.reset()

  private def runQuestion(question: String): String =
    componentClient
      .forAutonomousAgent(classOf[HelpDeskAgent], UUID.randomUUID().toString)
      .runSingleTask(HelpDeskTasks.ANSWER.instructions(question))

  /** Direct completion: the model answers without consulting the knowledge base (citedTopics empty).
    * This exercises the R3 schema-gen / round-trip for the Scala `HelpAnswer`.
    */
  @Test
  def completesWithTypedResultWithoutLookup(): Unit =
    model.fixedResponse(
      completeTask(HelpAnswer("We are open 24/7.", "general", java.util.List.of(), 95))
    )

    val taskId = runQuestion("What are your support hours?")

    Awaitility
      .await()
      .atMost(Duration.ofSeconds(20))
      .ignoreExceptions()
      .untilAsserted { () =>
        val snapshot = componentClient.forTask(taskId).get(HelpDeskTasks.ANSWER)
        assertThat(snapshot.status()).isEqualTo(TaskStatus.COMPLETED)
        val result = snapshot.result().get()
        assertThat(result.answer).isEqualTo("We are open 24/7.")
        assertThat(result.category).isEqualTo("general")
        assertThat(result.citedTopics).isEmpty()
        assertThat(result.confidence).isEqualTo(95)
      }

  /** Tool-consulting iteration: the model calls `lookupPolicy` first, then — reacting to the tool
    * result — completes with a `HelpAnswer` whose citedTopics reflects the consultation (contract C5).
    */
  @Test
  def consultsKnowledgeBaseThenCompletes(): Unit =
    model
      .whenMessage(_.contains("password"))
      .reply(new TestModelProvider.ToolInvocationRequest("lookupPolicy", """{"topic":"password-reset"}"""))
    model
      .whenToolResult(_.name() == "lookupPolicy")
      .reply(
        completeTask(
          HelpAnswer(
            "Use \"Forgot password\" on the sign-in page; the reset link expires in 30 minutes.",
            "account",
            java.util.List.of("password-reset"),
            90
          )
        )
      )

    val taskId = runQuestion("How do I reset my password?")

    Awaitility
      .await()
      .atMost(Duration.ofSeconds(20))
      .ignoreExceptions()
      .untilAsserted { () =>
        val snapshot = componentClient.forTask(taskId).get(HelpDeskTasks.ANSWER)
        assertThat(snapshot.status()).isEqualTo(TaskStatus.COMPLETED)
        val result = snapshot.result().get()
        assertThat(result.category).isEqualTo("account")
        assertThat(result.citedTopics).contains("password-reset")
      }
