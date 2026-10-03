package com.gwgs.akkaagentic.wallet.probe

import akka.javasdk.eventsourcedentity.EventSourcedEntityContext
import akka.javasdk.testkit.EventSourcedTestKit
import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.Test

/** Phase 0 probe — Q-E: the unit `EventSourcedTestKit` is on the method-reference wall.
  *
  * `.of(Function[Context, ES])` takes a plain SAM, so CONSTRUCTING the testkit from a Scala lambda is
  * fine. But `.method(Function[ES, Effect[R]])` resolves the command's return type via
  * `akka.javasdk.impl.reflection.Reflect.getReturnType`, which reads `method.getGenericReturnType` and
  * casts it to `ParameterizedType` to pull `R` out of `Effect[R]`. A Java method reference
  * (`WalletEntity::open`, see WalletJavaUnitProbeTest) resolves to the real command method, whose return
  * type is the parameterized `Effect[Done]`. A Scala lambda resolves to a synthetic method whose return
  * type is erased to a raw `Class`, so the cast throws `ClassCastException`. This test pins that — the
  * entity is therefore unit-tested from Java, and its domain rules (in the real capability) from pure
  * Scala.
  */
class WalletProbeTest:

  private def newKit(): EventSourcedTestKit[Wallet, WalletEvent, WalletEntity] =
    EventSourcedTestKit.of[Wallet, WalletEvent, WalletEntity]((ctx: EventSourcedEntityContext) =>
      new WalletEntity(ctx)
    )

  @Test
  def constructingTheTestkitFromAScalaLambdaWorks(): Unit =
    // the `.of` factory takes a SAM the SDK only stores — no return-type reflection yet
    assertThat(newKit()).isNotNull()

  @Test
  def drivingACommandWithAScalaLambdaHitsTheMethodReferenceWall(): Unit =
    val kit = newKit()
    assertThatThrownBy(() => kit.method((e: WalletEntity) => e.open(100L)).invoke())
      .isInstanceOf(classOf[ClassCastException])
