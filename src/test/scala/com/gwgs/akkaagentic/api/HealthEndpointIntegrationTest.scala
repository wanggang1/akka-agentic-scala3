package com.gwgs.akkaagentic.api

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.testkit.{TestKit, TestKitSupport}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Confirms the second Scala endpoint is discovered from the hand-maintained
  * descriptor and served over HTTP. Booting the runtime here is itself the
  * registration check; the request asserts it actually responds.
  */
class HealthEndpointIntegrationTest extends TestKitSupport:

  override protected def testKitSettings(): TestKit.Settings =
    // The agent component still boots even though this endpoint never calls it.
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")

  @Test
  def healthReturnsOk(): Unit =
    val response = httpClient
      .GET("/health")
      .responseBodyAs(classOf[HealthEndpoint.Health])
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.OK)
    assertThat(response.body().status).isEqualTo("ok")
