package com.gwgs.akkaagentic.grpc.api

import akka.grpc.GrpcServiceException
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.GrpcEndpoint
import akka.javasdk.client.ComponentClient
import com.gwgs.akkaagentic.application.GreetingAgent
import com.gwgs.akkaagentic.domain.{GreetingRequest, ValidGreeting}
import com.gwgs.akkaagentic.grpc.proto.{GreeterGrpcEndpoint, GreetReply, GreetRequest}
import io.grpc.Status

import java.util.UUID

/** Capability 19 (A4) — the project's first gRPC endpoint, authored in Scala.
  *
  * A Scala class implementing the protoc-generated Java interface
  * `com.gwgs.akkaagentic.grpc.proto.GreeterGrpcEndpoint` under `@GrpcEndpoint`. It is a
  * thin front over cap-1's `greeting-agent`: it forwards the request to the agent via the
  * agent client's `dynamicCall` (Scala-clean — the agent client is on the right side of
  * the method-ref wall) and maps the structured reply onto the protobuf `GreetReply`.
  *
  * The A4 finding is build ordering, not the wall: the SDK's Java-flavor gRPC codegen runs
  * at `generate-sources`, before this project's Scala compile (`process-resources`,
  * `sendJavaToScalac=true`), so this Scala class compiles against a Java interface that did
  * not exist until mid-build — confirmed from a clean build (specs/021 research Q-B).
  *
  * Input is validated at this boundary via the pure domain [[GreetingRequest]]
  * (parse-don't-validate): an empty user or message is rejected with `INVALID_ARGUMENT`
  * before the agent is called, so the model never sees empty input.
  */
@GrpcEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class GreeterGrpcEndpointImpl(componentClient: ComponentClient) extends GreeterGrpcEndpoint:
  import GreeterGrpcEndpointImpl.toApi

  override def greet(in: GreetRequest): GreetReply =
    // proto3 scalars are never null, so an empty string is "absent"; the domain's `validate`
    // treats a blank field as missing and returns the first failing message.
    GreetingRequest(Option(in.getUser), Option(in.getText)).validate match
      case Left(message) =>
        throw new GrpcServiceException(Status.INVALID_ARGUMENT.augmentDescription(message))
      case Right(valid) =>
        toApi(callAgent(valid, blankToNull(in.getTimezone)))

  private def callAgent(valid: ValidGreeting, timezone: String): GreetingAgent.Result =
    componentClient
      .forAgent()
      .inSession(UUID.randomUUID().toString)
      // GreetingAgent.Request stays Java-shaped (nullable `String` timezone): it travels the
      // component-command serializer's SEPARATE internal mapper (feature 003 R6). proto3
      // scalars are never null, so "absent" is the empty string — bridge "" -> null here.
      .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")
      .invoke(GreetingAgent.Request(valid.user, valid.text, timezone))

  /** proto3 string default is "" (never null). Treat blank as absent so the agent falls
    * back to UTC, matching the HTTP endpoint's `Option(..).filter(_.nonEmpty)` semantics.
    */
  private def blankToNull(timezone: String): String =
    if timezone == null || timezone.isBlank then null else timezone

object GreeterGrpcEndpointImpl:

  /** Map the application result to the protobuf wire type (API isolation, Constitution II):
    * the agent's/domain's own types are never exposed on the wire.
    */
  private def toApi(result: GreetingAgent.Result): GreetReply =
    GreetReply
      .newBuilder()
      .setGreeting(result.greeting)
      .setTone(result.tone)
      .setTimeOfDay(result.timeOfDay)
      .build()
