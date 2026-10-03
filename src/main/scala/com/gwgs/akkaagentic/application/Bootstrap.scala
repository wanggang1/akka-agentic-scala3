package com.gwgs.akkaagentic.application

import akka.javasdk.DependencyProvider
import akka.javasdk.JsonSupport
import akka.javasdk.ServiceSetup
import akka.javasdk.annotations.Setup
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.gwgs.akkaagentic.compaction.application.CompactionSettings
import com.gwgs.akkaagentic.docs.application.KnowledgeStore
import com.typesafe.config.Config

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
class Bootstrap(config: Config) extends ServiceSetup:

  override def onStartup(): Unit =
    JsonSupport.getObjectMapper().registerModule(DefaultScalaModule)
    validateCompactionSettings()

  /** Capability 17: refuse to start on an out-of-range `compaction.*` value.
    *
    * Measured before deciding this (specs/019 T029): without it, an out-of-range `max-bytes` produces a
    * service that **looks healthy** — it starts, user turns answer normally, and nothing surfaces — while
    * `SessionMemoryConsumer` throws on construction for **every** session-memory event. 40 redeliveries
    * were counted in a single short test. Capability 16 measured that a failing consumer is redelivered
    * without limit and **blocks every other entity behind it**, so this is not "compaction quietly does
    * not work": it is a typo that can stall the whole session-memory projection, with no symptom pointing
    * at the cause.
    *
    * So the failure is moved to the one place it cannot be missed, which is the shape capability 12 landed
    * on for a misspelled guardrail class: **there is no window in which the service is silently not
    * compacting.** `CompactionSettings` already raises `ConfigException.BadValue`; calling it here simply
    * makes that happen at startup rather than on every delivery for ever.
    */
  private def validateCompactionSettings(): Unit =
    CompactionSettings.threshold(config)
    CompactionSettings.maxSessions(config)

  /** Provide capability 8's [[KnowledgeStore]] as a custom, constructor-injectable dependency.
    *
    * This is the SDK's sanctioned path for injecting a non-SDK dependency (the same shape as the
    * *Ask Akka* RAG sample's `Knowledge` bootstrap, in Java). The store is built **once**, eagerly,
    * here — which seeds the in-memory vector store and loads the in-process ONNX embedding model at
    * service (and TestKit) startup — then handed to any component whose constructor asks for a
    * `KnowledgeStore` (the `DocsEndpoint`).
    *
    * Interop note (Scala on the Java-first SDK): `DependencyProvider` is keyed on `Class`, not a Java
    * method reference, so — like the Agent/AutonomousAgent/Task clients (§5, §7) and unlike the
    * Workflow/entity clients (§4, §6) — it is cleanly implementable and wired from Scala with no
    * method-reference wall. This is the project's first injection of a dependency other than the
    * SDK-provided `ComponentClient`.
    */
  override def createDependencyProvider(): DependencyProvider =
    val knowledgeStore = KnowledgeStore.fromCorpus()
    new DependencyProvider:
      override def getDependency[T](cls: Class[T]): T =
        if cls == classOf[KnowledgeStore] then knowledgeStore.asInstanceOf[T]
        else null.asInstanceOf[T]
