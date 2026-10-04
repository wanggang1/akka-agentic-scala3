package com.gwgs.akkaagentic.wallet.domain

/** The wallet domain model (capability 18 / A3) — state plus the rules that decide what is legal.
  *
  * Pure and SDK-free (FR-009): it names no Akka type, so it is unit-tested in isolation (SC-005). The
  * entity ([[com.gwgs.akkaagentic.wallet.application.WalletEntity]]) chooses effects; this model decides
  * legality and produces the event. Parse-don't-validate: a `try*` method returns `Right(event)` only
  * when the operation is allowed, so the entity never re-checks before persisting.
  *
  * The transition methods are named `try*` (not `open`/`close`/…) because a Scala `val open` and a
  * `def open(...)` cannot coexist; the state field stays `open` so the serialized snapshot shape is the
  * one measured in Phase 0 (research Q-C).
  *
  * Each accepted operation yields a [[WalletEvent]] carrying the resulting balance, so [[applyEvent]] is a
  * pure copy — the arithmetic lives here, once, before anything is persisted.
  */
final case class Wallet(balance: Long, open: Boolean):

  def tryOpen(startingBalance: Long): Either[String, WalletEvent] =
    if open then Left("wallet already open")
    else if startingBalance < 0 then Left("starting balance must be non-negative")
    else Right(new WalletEvent.Opened(startingBalance))

  def tryDeposit(amount: Long): Either[String, WalletEvent] =
    if !open then Left("wallet not open")
    else if amount <= 0 then Left("amount must be positive")
    else Right(new WalletEvent.Deposited(amount, balance + amount))

  def tryWithdraw(amount: Long): Either[String, WalletEvent] =
    if !open then Left("wallet not open")
    else if amount <= 0 then Left("amount must be positive")
    else if amount > balance then Left("insufficient funds")
    else Right(new WalletEvent.Withdrawn(amount, balance - amount))

  def tryClose: Either[String, WalletEvent] =
    if !open then Left("wallet not open")
    else Right(new WalletEvent.Closed(balance))

  /** Fold one event into the next state. Total and never failing: validation precedes every persist. */
  def applyEvent(event: WalletEvent): Wallet =
    event match
      case o: WalletEvent.Opened    => Wallet(o.startingBalance, open = true)
      case d: WalletEvent.Deposited => copy(balance = d.balance)
      case w: WalletEvent.Withdrawn => copy(balance = w.balance)
      case _: WalletEvent.Closed    => copy(open = false)

object Wallet:
  /** A wallet with no events: closed, zero balance. */
  val empty: Wallet = Wallet(0L, open = false)
