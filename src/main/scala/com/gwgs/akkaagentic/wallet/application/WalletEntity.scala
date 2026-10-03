package com.gwgs.akkaagentic.wallet.application

import akka.Done
import akka.javasdk.annotations.Component
import akka.javasdk.eventsourcedentity.{EventSourcedEntity, EventSourcedEntityContext}
import com.gwgs.akkaagentic.wallet.domain.{Wallet, WalletEvent}

/** The wallet as an Event Sourced Entity (capability 18 / A3) — the first in this repo.
  *
  * Scala, and it is the entity itself, not only its tests: the SDK forces Java on exactly two things here,
  * and neither is this class — the event hierarchy ([[WalletEvent]], a Java sealed interface the SDK's
  * startup validation requires) and the caller ([[com.gwgs.akkaagentic.wallet.api.WalletEndpoint]], Java
  * because the entity client is method-reference-only). The state [[Wallet]] is a Scala case class that
  * round-trips through the internal mapper for snapshots (research Q-C).
  *
  * Thin handlers: all legality lives in the domain (FR-009). Each handler asks [[Wallet]] for the event,
  * persists it on `Right`, and returns `effects().error` on `Left` — which surfaces to the Java caller as
  * a `CommandException` it maps to `400`. `applyEvent` delegates straight to the domain fold.
  */
@Component(id = "wallet")
class WalletEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Wallet, WalletEvent]:

  override def emptyState(): Wallet = Wallet.empty

  def open(startingBalance: Long): EventSourcedEntity.Effect[Done] =
    persistOrError(currentState().tryOpen(startingBalance))

  def deposit(amount: Long): EventSourcedEntity.Effect[Done] =
    persistOrError(currentState().tryDeposit(amount))

  def withdraw(amount: Long): EventSourcedEntity.Effect[Done] =
    persistOrError(currentState().tryWithdraw(amount))

  def close(): EventSourcedEntity.Effect[Done] =
    persistOrError(currentState().tryClose)

  /** Full state, read-only. The endpoint maps it to its own API type (never returns `Wallet`). */
  def get(): EventSourcedEntity.ReadOnlyEffect[Wallet] = effects().reply(currentState())

  override def applyEvent(event: WalletEvent): Wallet = currentState().applyEvent(event)

  private def persistOrError(result: Either[String, WalletEvent]): EventSourcedEntity.Effect[Done] =
    result match
      case Right(event) => effects().persist(event).thenReply(_ => Done.getInstance())
      case Left(error)  => effects().error(error)
