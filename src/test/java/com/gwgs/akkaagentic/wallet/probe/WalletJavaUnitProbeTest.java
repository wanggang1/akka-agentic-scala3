package com.gwgs.akkaagentic.wallet.probe;

import akka.javasdk.testkit.EventSourcedTestKit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 0 probe control — the SAME Scala entity, driven from a JAVA unit test with a method
 *  reference (WalletEntity::open). If this works where the Scala-lambda testkit threw in
 *  Reflect.getReturnType, the wall is confirmed to be the lambda, not the entity. */
public class WalletJavaUnitProbeTest {

  @Test
  public void javaMethodRefDrivesTheScalaEntity() {
    var kit = EventSourcedTestKit.of(WalletEntity::new);

    kit.method(WalletEntity::open).invoke(100L);
    kit.method(WalletEntity::deposit).invoke(50L);
    kit.method(WalletEntity::withdraw).invoke(30L);

    assertThat(kit.getState().balance()).isEqualTo(120L);
    assertThat(kit.getState().open()).isTrue();
    assertThat(kit.getAllEvents().size()).isEqualTo(3);
  }
}
