package com.gwgs.akkaagentic.wallet.probe

import akka.Done
import akka.javasdk.annotations.Component
import akka.javasdk.eventsourcedentity.{EventSourcedEntity, EventSourcedEntityContext}

/** Phase 0 probe (specs/020, capability 18 / A3) — the FIRST event-sourced entity in this repo.
  *
  * Measured interop shape (see specs/020 research):
  *   - EVENTS are Java-authored (`WalletEvent`, same package): a Scala 3 `enum` serializes but cannot be
  *     deserialized, and a Scala 3 `sealed trait` fails the SDK's "must be a sealed interface" startup
  *     validation (`isSealed == false`). A Java `sealed interface` is a true JVM-sealed interface.
  *   - STATE (`Wallet`) stays an idiomatic Scala case class — it round-trips through the internal mapper
  *     for snapshots without any Jackson annotations (Q-C).
  *   - The ENTITY is Scala (compiles and runs); its CALLER is Java because the component client is
  *     method-reference-only (Q-D).
  *
  * Business rules are inline here on purpose — the probe is throwaway; the real capability will push them
  * into a pure domain model (spec FR-009).
  */
final case class Wallet(balance: Long, open: Boolean)

@Component(id = "wallet-probe")
class WalletEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Wallet, WalletEvent]:

  override def emptyState(): Wallet = Wallet(0L, open = false)

  def open(startingBalance: Long): EventSourcedEntity.Effect[Done] =
    if currentState().open then effects().error("wallet already open")
    else if startingBalance < 0 then effects().error("starting balance must be non-negative")
    else effects().persist(new WalletEvent.Opened(startingBalance)).thenReply(_ => Done.getInstance())

  def deposit(amount: Long): EventSourcedEntity.Effect[Done] =
    if !currentState().open then effects().error("wallet not open")
    else if amount <= 0 then effects().error("amount must be positive")
    else
      effects()
        .persist(new WalletEvent.Deposited(amount, currentState().balance + amount))
        .thenReply(_ => Done.getInstance())

  def withdraw(amount: Long): EventSourcedEntity.Effect[Done] =
    if !currentState().open then effects().error("wallet not open")
    else if amount <= 0 then effects().error("amount must be positive")
    else if amount > currentState().balance then effects().error("insufficient funds")
    else
      effects()
        .persist(new WalletEvent.Withdrawn(amount, currentState().balance - amount))
        .thenReply(_ => Done.getInstance())

  def close(): EventSourcedEntity.Effect[Done] =
    if !currentState().open then effects().error("wallet not open")
    else effects().persist(new WalletEvent.Closed(currentState().balance)).thenReply(_ => Done.getInstance())

  def getBalance(): EventSourcedEntity.ReadOnlyEffect[Long] = effects().reply(currentState().balance)

  override def applyEvent(event: WalletEvent): Wallet =
    event match
      case o: WalletEvent.Opened    => Wallet(o.startingBalance, open = true)
      case d: WalletEvent.Deposited => currentState().copy(balance = d.balance)
      case w: WalletEvent.Withdrawn => currentState().copy(balance = w.balance)
      case _: WalletEvent.Closed    => currentState().copy(open = false)
