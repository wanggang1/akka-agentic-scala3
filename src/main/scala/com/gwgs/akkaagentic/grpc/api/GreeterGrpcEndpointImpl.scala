package com.gwgs.akkaagentic.grpc.api

import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.GrpcEndpoint
import akka.javasdk.client.ComponentClient
import com.gwgs.akkaagentic.grpc.proto.{GreeterGrpcEndpoint, GreetReply, GreetRequest}

/** Capability 19 (A4) — the project's first gRPC endpoint, authored in Scala.
  *
  * STUB (task T003): implements the protoc-generated Java interface
  * `com.gwgs.akkaagentic.grpc.proto.GreeterGrpcEndpoint` with a hardcoded reply and
  * NO agent call. Its only job is to prove the build-ordering bet of A4 — that the
  * SDK's Java-flavor gRPC codegen (run at `generate-sources`) lands on scalac's source
  * path before this project's Scala compile (bound to `process-resources`, with
  * `sendJavaToScalac=true`), so a Scala class can implement a Java interface whose
  * source does not exist until mid-build — measured from `mvn clean verify`.
  *
  * The real agent call (via the agent client's `dynamicCall`) and input validation are
  * added in tasks T008/T009 (US1) and T011 (US2). `componentClient` is injected now to
  * confirm DI into a gRPC endpoint works; it is unused by the stub.
  */
@GrpcEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class GreeterGrpcEndpointImpl(componentClient: ComponentClient) extends GreeterGrpcEndpoint:

  override def greet(in: GreetRequest): GreetReply =
    GreetReply
      .newBuilder()
      .setGreeting("stub")
      .setTone("stub")
      .setTimeOfDay("stub")
      .build()
