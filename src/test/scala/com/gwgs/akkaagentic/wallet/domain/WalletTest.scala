package com.gwgs.akkaagentic.wallet.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure unit tests for the wallet rules — **no TestKit, no runtime, no model** (SC-005, FR-009).
  *
  * The whole point of keeping the rules in a plain `case class` is that every legality decision and every
  * balance folds here in milliseconds; the (slower) integration tests then prove only the wiring.
  */
class WalletTest:

  /** Fold a sequence of accepted events onto `empty`, asserting each step was allowed. */
  private def fold(ops: (Wallet => Either[String, WalletEvent])*): Wallet =
    ops.foldLeft(Wallet.empty) { (w, op) =>
      op(w) match
        case Right(event) => w.applyEvent(event)
        case Left(err)    => throw new AssertionError(s"expected the op to be allowed, got: $err")
    }

  // --- US1: the fold ------------------------------------------------------------------------------

  @Test
  def openThenDepositsAndWithdrawalsFoldToTheSignedSum(): Unit =
    val w = fold(_.tryOpen(100L), _.tryDeposit(50L), _.tryWithdraw(30L))
    assertThat(w.balance).isEqualTo(120L)
    assertThat(w.open).isTrue()

  @Test
  def anEmptyWalletIsClosedWithZeroBalance(): Unit =
    assertThat(Wallet.empty.balance).isEqualTo(0L)
    assertThat(Wallet.empty.open).isFalse()

  @Test
  def withdrawingTheEntireBalanceIsAllowedAndLeavesZero(): Unit =
    val w = fold(_.tryOpen(40L), _.tryWithdraw(40L))
    assertThat(w.balance).isEqualTo(0L)
    assertThat(w.open).isTrue()

  // --- US2: rejections yield Left and produce no event --------------------------------------------

  @Test
  def cannotOpenAnAlreadyOpenWallet(): Unit =
    val opened = Wallet.empty.applyEvent(Wallet.empty.tryOpen(10L).toOption.get)
    assertThat(opened.tryOpen(5L).isLeft).isTrue()

  @Test
  def cannotOpenWithANegativeStartingBalance(): Unit =
    assertThat(Wallet.empty.tryOpen(-1L).isLeft).isTrue()

  @Test
  def cannotOverdraw(): Unit =
    val opened = Wallet.empty.applyEvent(Wallet.empty.tryOpen(10L).toOption.get)
    assertThat(opened.tryWithdraw(11L).isLeft).isTrue()

  @Test
  def cannotDepositOrWithdrawANonPositiveAmount(): Unit =
    val opened = Wallet.empty.applyEvent(Wallet.empty.tryOpen(10L).toOption.get)
    assertThat(opened.tryDeposit(0L).isLeft).isTrue()
    assertThat(opened.tryDeposit(-5L).isLeft).isTrue()
    assertThat(opened.tryWithdraw(0L).isLeft).isTrue()

  @Test
  def aClosedWalletRefusesEveryOperation(): Unit =
    val closed = fold(_.tryOpen(10L), _.tryClose)
    assertThat(closed.open).isFalse()
    assertThat(closed.tryDeposit(5L).isLeft).isTrue()
    assertThat(closed.tryWithdraw(1L).isLeft).isTrue()
    assertThat(closed.tryClose.isLeft).isTrue()

  @Test
  def cannotOperateOnAWalletThatWasNeverOpened(): Unit =
    assertThat(Wallet.empty.tryDeposit(5L).isLeft).isTrue()
    assertThat(Wallet.empty.tryWithdraw(5L).isLeft).isTrue()
    assertThat(Wallet.empty.tryClose.isLeft).isTrue()

  // --- the event carries the resulting balance (so applyEvent needs no arithmetic) ----------------

  @Test
  def eachEventCarriesTheResultingBalance(): Unit =
    val opened = Wallet.empty.applyEvent(Wallet.empty.tryOpen(100L).toOption.get)
    opened.tryWithdraw(30L) match
      case Right(w: WalletEvent.Withdrawn) =>
        assertThat(w.amount).isEqualTo(30L)
        assertThat(w.balance).isEqualTo(70L)
      case other => throw new AssertionError(s"expected a Withdrawn event, got: $other")
