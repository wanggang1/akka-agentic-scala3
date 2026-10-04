package com.gwgs.akkaagentic.wallet.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.gwgs.akkaagentic.wallet.domain.Wallet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 — snapshots do not corrupt the fold (capability 18).
 *
 * <p>{@code snapshot-every} is lowered to 2 <strong>only for this test</strong>, via
 * {@code withAdditionalConfig}. The knob is service-wide — there is no per-entity form (research "Bonus:
 * snapshot-every is global") — so lowering it in {@code application.conf} would also change the
 * runtime-owned {@code SessionMemoryEntity} that capabilities 4/6/17 depend on. Production therefore keeps
 * the SDK default (100), and snapshotting is exercised here under override.
 *
 * <p>This drives more events than the threshold and confirms the folded balance stays exact while
 * snapshots are being written for real. What it does <strong>not</strong> do is force a reload from a
 * snapshot: {@code TestKitSupport} exposes no passivation hook, so reload-equivalence is proven instead by
 * the serializer round-trip in {@code WalletSerializationTest} (Q-C) — the honest boundary, stated rather
 * than glossed.
 */
public class WalletSnapshotIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withAdditionalConfig("akka.javasdk.event-sourced-entity.snapshot-every = 2");
  }

  @Test
  public void theFoldStaysExactAcrossSnapshotWrites() {
    var id = "snapshot-wallet";

    componentClient.forEventSourcedEntity(id).method(WalletEntity::open).invoke(100L);
    // five more events, well past snapshot-every = 2, so several snapshots are written for real
    componentClient.forEventSourcedEntity(id).method(WalletEntity::deposit).invoke(10L);
    componentClient.forEventSourcedEntity(id).method(WalletEntity::deposit).invoke(20L);
    componentClient.forEventSourcedEntity(id).method(WalletEntity::withdraw).invoke(5L);
    componentClient.forEventSourcedEntity(id).method(WalletEntity::deposit).invoke(30L);
    componentClient.forEventSourcedEntity(id).method(WalletEntity::withdraw).invoke(15L);

    Wallet wallet = componentClient.forEventSourcedEntity(id).method(WalletEntity::get).invoke();
    assertThat(wallet.balance()).isEqualTo(140L); // 100 + 10 + 20 - 5 + 30 - 15
    assertThat(wallet.open()).isTrue();
  }
}
