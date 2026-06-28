package com.gwgs.akkaagentic.api

import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{Get, HttpEndpoint}
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

object HealthEndpoint:

  /** Outbound wire type — never expose domain/application types (Constitution II). */
  final case class Health @JsonCreator() (
      @JsonProperty("status") status: String
  )

/** Liveness probe. Stateless, no component calls — exists mainly to confirm a
  * second Scala component is discovered from the hand-maintained descriptor.
  */
@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class HealthEndpoint:
  import HealthEndpoint.*

  @Get("/health")
  def health(): Health =
    Health("ok")
