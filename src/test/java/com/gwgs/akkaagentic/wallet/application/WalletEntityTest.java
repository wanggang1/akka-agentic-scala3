package com.gwgs.akkaagentic.wallet.application;

import akka.javasdk.testkit.EventSourcedTestKit;
import com.gwgs.akkaagentic.wallet.domain.Wallet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity unit tests (capability 18) — the core US2 property: a rejected command persists <strong>no</strong>
 * event, so the journal can never replay a mistake (SC-002/SC-003).
 *
 * <p>Java, driven by method references (research Q-E): the unit {@code EventSourcedTestKit.method(...)}
 * resolves the command's return type by reflection and a Scala lambda has no parameterized return type, so
 * it throws in {@code Reflect.getReturnType}. A Java method reference ({@code WalletEntity::open}) resolves
 * the real command method. The domain rules themselves are proven in pure Scala
 * ({@code com.gwgs.akkaagentic.wallet.domain.WalletTest}); this test proves the entity wiring —
 * specifically the persist/no-persist decision — over the testkit's real serialization round-trip.
 */
public class WalletEntityTest {

  @Test
  public void acceptedCommandsEachPersistExactlyOneEvent() {
    var kit = EventSourcedTestKit.of(WalletEntity::new);

    kit.method(WalletEntity::open).invoke(100L);
    kit.method(WalletEntity::deposit).invoke(50L);
    kit.method(WalletEntity::withdraw).invoke(30L);

    assertThat(kit.getAllEvents()).hasSize(3);
    assertThat(((Wallet) kit.getState()).balance()).isEqualTo(120L);
    assertThat(((Wallet) kit.getState()).open()).isTrue();
  }

  @Test
  public void aRejectedCommandPersistsNoEventAndDoesNotMoveTheBalance() {
    var kit = EventSourcedTestKit.of(WalletEntity::new);
    kit.method(WalletEntity::open).invoke(100L);
    int before = kit.getAllEvents().size();

    var overdraw = kit.method(WalletEntity::withdraw).invoke(999L);

    assertThat(overdraw.isError()).isTrue();
    assertThat(kit.getAllEvents()).hasSize(before); // the event count did not advance
    assertThat(((Wallet) kit.getState()).balance()).isEqualTo(100L);
  }

  @Test
  public void everyForbiddenOperationIsAnErrorThatRecordsNothing() {
    var kit = EventSourcedTestKit.of(WalletEntity::new);

    // never opened
    assertThat(kit.method(WalletEntity::deposit).invoke(5L).isError()).isTrue();
    assertThat(kit.method(WalletEntity::withdraw).invoke(5L).isError()).isTrue();
    assertThat(kit.getAllEvents()).isEmpty();

    kit.method(WalletEntity::open).invoke(10L);
    assertThat(kit.method(WalletEntity::open).invoke(5L).isError()).isTrue(); // re-open
    assertThat(kit.method(WalletEntity::deposit).invoke(0L).isError()).isTrue(); // non-positive
    assertThat(kit.method(WalletEntity::withdraw).invoke(-1L).isError()).isTrue();

    kit.method(WalletEntity::close).invoke();
    assertThat(kit.method(WalletEntity::deposit).invoke(5L).isError()).isTrue(); // closed

    // only the two accepted commands (open, close) left events
    assertThat(kit.getAllEvents()).hasSize(2);
    assertThat(((Wallet) kit.getState()).open()).isFalse();
  }
}
