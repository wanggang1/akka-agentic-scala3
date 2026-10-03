package com.gwgs.akkaagentic.wallet.domain;

import akka.javasdk.annotations.TypeName;

/**
 * The wallet's event hierarchy (capability 18 / A3).
 *
 * <p>This is the one file this otherwise-Scala capability authors in Java, and the reason is measured
 * (specs/020 research Q-A/Q-B): an event-sourced entity's event type must be a <em>sealed interface</em>
 * — the SDK validates this at startup. A Scala 3 {@code enum} serializes but cannot be deserialized
 * ("Cannot reflectively create enum objects"); a Scala 3 {@code sealed trait} round-trips through the
 * internal mapper but fails startup validation because Scala 3.3.8 emits no JVM {@code sealed} attribute
 * ({@code isSealed() == false}, {@code getPermittedSubclasses() == null}). A Java {@code sealed interface}
 * is a genuine JVM-sealed interface, so the events live here while the {@code Wallet} state and the entity
 * stay Scala.
 *
 * <p>Each event carries the <em>resulting</em> balance so the fold ({@code Wallet.applyEvent}) is a pure
 * copy with no arithmetic — the arithmetic, and its validation, happened once before the event was
 * persisted.
 */
public sealed interface WalletEvent {

  @TypeName("opened")
  record Opened(long startingBalance) implements WalletEvent {}

  @TypeName("deposited")
  record Deposited(long amount, long balance) implements WalletEvent {}

  @TypeName("withdrawn")
  record Withdrawn(long amount, long balance) implements WalletEvent {}

  @TypeName("closed")
  record Closed(long finalBalance) implements WalletEvent {}
}
