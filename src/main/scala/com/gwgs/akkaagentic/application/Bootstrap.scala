package com.gwgs.akkaagentic.application

import akka.javasdk.JsonSupport
import akka.javasdk.ServiceSetup
import akka.javasdk.annotations.Setup
import com.fasterxml.jackson.module.scala.DefaultScalaModule

/** Service lifecycle hook: makes the SDK's shared Jackson `ObjectMapper` Scala-aware.
  *
  * The SDK (de)serializes every wire type through one predefined `ObjectMapper` that, by
  * default, does not understand Scala — a spike confirmed only the jdk8/jsr310/parameter-names
  * modules are registered. Registering [[DefaultScalaModule]] here lets wire types be ordinary
  * annotation-free Scala case classes with `Option` fields (present → `Some`, absent/null →
  * `None`), removing the per-type `@JsonCreator`/`@JsonProperty` annotations and the manual
  * `null → None` boundary conversions. The module is already on the classpath transitively
  * (`jackson-module-scala_2.13`, matching the SDK's Jackson version), so this adds no dependency.
  *
  * `JsonSupport.getObjectMapper()` is the SDK-sanctioned customization hook (see the SDK
  * serialization docs). Registration is additive: existing Java-shaped types that keep their
  * Jackson annotations continue to work unchanged.
  *
  * Discovery note (Scala on the Java-first SDK): the SDK's annotation processor only scans Java
  * sources, so this `@Setup` class is registered by hand in the component descriptor under the
  * top-level `akka.javasdk.service-setup` entry (a single FQCN, sibling of
  * `akka.javasdk.components`). Both the runtime and the TestKit locate it that way, so
  * `onStartup` also runs during the offline test suite.
  */
@Setup
class Bootstrap extends ServiceSetup:

  override def onStartup(): Unit =
    JsonSupport.getObjectMapper().registerModule(DefaultScalaModule)
