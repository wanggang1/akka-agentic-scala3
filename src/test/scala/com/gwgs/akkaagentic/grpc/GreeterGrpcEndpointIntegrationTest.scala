package com.gwgs.akkaagentic.grpc

import akka.javasdk.JsonSupport
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.application.GreetingAgent
import com.gwgs.akkaagentic.grpc.proto.{GreeterGrpcEndpointClient, GreetRequest}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Drives [[com.gwgs.akkaagentic.grpc.api.GreeterGrpcEndpointImpl]] over gRPC with a
  * mocked model (no live model). Capability 19 (A4): the first gRPC endpoint in the repo.
  *
  * Uses the generated blocking client `GreeterGrpcEndpointClient` (confirmed in research
  * Q-E) obtained from the testkit; the model behind cap-1's `greeting-agent` is mocked
  * with [[TestModelProvider]], exactly as the HTTP `GreetingEndpointIntegrationTest` does.
  */
class GreeterGrpcEndpointIntegrationTest extends TestKitSupport:

  private val greetingModel = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[GreetingAgent], greetingModel)

  private def client: GreeterGrpcEndpointClient =
    getGrpcEndpointClient(classOf[GreeterGrpcEndpointClient])

  /** US1 scenario 1: a valid request returns a reply carrying all three fields, mapped
    * from the agent's structured `Result`. This is the capability end to end over gRPC.
    */
  @Test
  def validRequestReturnsStructuredReply(): Unit =
    val mocked = GreetingAgent.Result("Hello Ada! Lovely to hear from you.", "casual", "morning")
    greetingModel.fixedResponse(JsonSupport.encodeToString(mocked))

    val reply = client.greet(
      GreetRequest.newBuilder().setUser("Ada").setText("hello there").build()
    )

    assertThat(reply.getGreeting).isEqualTo("Hello Ada! Lovely to hear from you.")
    assertThat(reply.getTone).isEqualTo("casual")
    assertThat(reply.getTimeOfDay).isEqualTo("morning")

  /** US1 scenario 2: a supplied timezone is forwarded to the agent. The mock only replies
    * when the user message carries the timezone, so a match PROVES the endpoint passed it
    * through; otherwise the agent's `onFailure` fallback (neutral tone) would answer instead.
    */
  @Test
  def timezoneIsForwardedToAgent(): Unit =
    val mocked = GreetingAgent.Result("Good evening, Ada!", "warm", "evening")
    greetingModel
      .whenMessage(_.contains("Europe/London"))
      .reply(JsonSupport.encodeToString(mocked))

    val reply = client.greet(
      GreetRequest
        .newBuilder()
        .setUser("Ada")
        .setText("evening!")
        .setTimezone("Europe/London")
        .build()
    )

    assertThat(reply.getTone).isEqualTo("warm")
    assertThat(reply.getTimeOfDay).isEqualTo("evening")

  /** US1 scenario 3: with no timezone (proto default ""), the call still succeeds. */
  @Test
  def absentTimezoneStillSucceeds(): Unit =
    val mocked = GreetingAgent.Result("Hi Ada!", "casual", "afternoon")
    greetingModel.fixedResponse(JsonSupport.encodeToString(mocked))

    val reply = client.greet(
      GreetRequest.newBuilder().setUser("Ada").setText("hi").build()
    )

    assertThat(reply.getGreeting).isEqualTo("Hi Ada!")
    assertThat(reply.getTimeOfDay).isEqualTo("afternoon")
