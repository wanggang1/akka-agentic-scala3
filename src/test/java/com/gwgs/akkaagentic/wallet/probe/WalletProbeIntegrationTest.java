package com.gwgs.akkaagentic.wallet.probe;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 probe — boots the WHOLE service (so the Scala event-sourced entity must be discovered from the
 * hand-maintained descriptor under the {@code event-sourced-entity} key) and drives it over the real
 * runtime and journal: every accepted command is serialized and persisted for real, then the folded
 * balance is read back. If the sealed-trait events or the case-class state could not cross the internal
 * mapper on this path, boot or persist would fail here.
 *
 * <p>Java, not Scala, for the measured reason of Q-D: the {@code EventSourcedEntity} component client is
 * method-reference-only ({@code .method(WalletEntity::open)}) with no {@code dynamicCall}, exactly as
 * capability 6 found. A Scala lambda cannot resolve it — so the entity's caller is Java, as the
 * production endpoint will be. snapshot-every is set low so a snapshot is also WRITTEN during the run
 * (read-back of snapshot state is covered passivation-free by WalletSerializationProbeTest / Q-C).
 */
public class WalletProbeIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withAdditionalConfig("akka.javasdk.event-sourced-entity.snapshot-every = 2");
  }

  @Test
  public void bootsAndFoldsAcceptedCommandsOverTheRealJournal() {
    var id = "wallet-abc";

    componentClient.forEventSourcedEntity(id).method(WalletEntity::open).invoke(100L);
    componentClient.forEventSourcedEntity(id).method(WalletEntity::deposit).invoke(50L);
    componentClient.forEventSourcedEntity(id).method(WalletEntity::withdraw).invoke(30L);
    // enough events to cross snapshot-every = 2, so a Wallet-state snapshot is serialized for real
    componentClient.forEventSourcedEntity(id).method(WalletEntity::deposit).invoke(5L);

    Object balance = componentClient.forEventSourcedEntity(id).method(WalletEntity::getBalance).invoke();
    assertThat(((Number) balance).longValue()).isEqualTo(125L); // 100 + 50 - 30 + 5
  }

  @Test
  public void rejectedCommandsAreErrorsOverTheRealRuntime() {
    var id = "wallet-xyz";
    componentClient.forEventSourcedEntity(id).method(WalletEntity::open).invoke(10L);

    try {
      componentClient.forEventSourcedEntity(id).method(WalletEntity::withdraw).invoke(999L);
      org.junit.jupiter.api.Assertions.fail("overdraw should have been rejected");
    } catch (RuntimeException expected) {
      // the command handler returned effects().error(...)
    }

    Object balance = componentClient.forEventSourcedEntity(id).method(WalletEntity::getBalance).invoke();
    assertThat(((Number) balance).longValue()).isEqualTo(10L);
  }
}
