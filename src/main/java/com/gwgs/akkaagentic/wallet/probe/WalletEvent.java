package com.gwgs.akkaagentic.wallet.probe;

import akka.javasdk.annotations.TypeName;

/**
 * Phase 0 probe (specs/020) — the event hierarchy, Java-authored.
 *
 * <p>This is the measured fallback (spec Q-A/Q-B). A Scala 3 {@code enum} serializes but cannot be read
 * back ("Cannot reflectively create enum objects"); a Scala 3 {@code sealed trait} round-trips through
 * the serializer but fails the SDK's startup validation — {@code isSealed() == false} and
 * {@code getPermittedSubclasses() == null}, because Scala 3.3.8 does not emit the JVM sealed attribute,
 * and the SDK requires "the event type of an EventSourcedEntity to be a sealed interface". A Java
 * {@code sealed interface} is a true JVM-sealed interface, so the events must be Java-authored. The
 * entity and the {@code Wallet} state stay Scala (the state round-trips as a plain case class — Q-C).
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
