package com.gwgs.akkaagentic.feed.application

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import com.gwgs.akkaagentic.feed.application.BoundedDelivery.Outcome
import com.gwgs.akkaagentic.feed.domain.DeliveryKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** T012 — the bound that keeps one bad message from stalling every user, tested with no runtime. The real
  * projection's behaviour around it is proven separately, on the real path (T020). */
class BoundedDeliveryTest:

  private val consumer = "test-consumer"
  private val boom = RuntimeException("boom")

  @BeforeEach
  def reset(): Unit = ActivityStore.clear()

  private def run(user: String, fingerprint: String, limit: Int)(work: => Unit) =
    BoundedDelivery.run(consumer, user, fingerprint, limit)(work)

  @Test
  def successSpendsNothingAndLeavesNoSetAside(): Unit =
    assertThat(run("alice", "fp", 3)(())).isEqualTo(Outcome.Succeeded)
    assertThat(ActivityStore.feed.attemptsFor(DeliveryKey(consumer, "alice", "fp"))).isEqualTo(0)
    assertThat(ActivityStore.feed.setAsides.isEmpty).isTrue()

  @Test
  def aFailureWithAttemptsLeftAsksForARedeliveryAndCountsIt(): Unit =
    run("alice", "fp", 3)(throw boom) match
      case Outcome.WillRetry(attempt, cause) =>
        assertThat(attempt).isEqualTo(1)
        assertThat(cause).isSameAs(boom)
      case other => throw AssertionError(s"expected WillRetry, got $other")
    assertThat(ActivityStore.feed.setAsides.isEmpty).isTrue()

  @Test
  def theLastPermittedFailureSetsTheDeliveryAsideWithItsReason(): Unit =
    run("alice", "fp", 2)(throw boom)
    run("alice", "fp", 2)(throw boom) match
      case Outcome.GaveUp(attempt, _) => assertThat(attempt).isEqualTo(2)
      case other                      => throw AssertionError(s"expected GaveUp, got $other")
    val aside = ActivityStore.feed.setAsides.last
    assertThat(aside.username).isEqualTo("alice")
    assertThat(aside.attempts).isEqualTo(2)
    assertThat(aside.reason).contains("boom")
    // A set-aside is not activity — a reader must be able to tell them apart.
    assertThat(ActivityStore.feed.entries.isEmpty).isTrue()
    // Its count is spent, so a later redelivery of the same state starts over rather than being set
    // aside immediately.
    assertThat(ActivityStore.feed.attemptsFor(DeliveryKey(consumer, "alice", "fp"))).isEqualTo(0)

  @Test
  def aDifferentStateForTheSameUserIsCountedSeparately(): Unit =
    run("alice", "fp-1", 3)(throw boom)
    run("alice", "fp-1", 3)(throw boom)
    run("alice", "fp-2", 3)(throw boom) match
      case Outcome.WillRetry(attempt, _) => assertThat(attempt).isEqualTo(1)
      case other                         => throw AssertionError(s"expected WillRetry, got $other")

  @Test
  def concurrentAttemptsOnOneDeliveryAreCountedExactlyOnce(): Unit =
    // The store commits by compare-and-set; this is the check that no count is lost under a race.
    val racers = 32
    val pool = Executors.newFixedThreadPool(racers)
    val start = CountDownLatch(1)
    try
      val futures = (1 to racers).map(_ =>
        pool.submit(() => { start.await(); run("alice", "fp", Int.MaxValue)(throw boom) }))
      start.countDown()
      futures.foreach(_.get(10, TimeUnit.SECONDS))
      assertThat(ActivityStore.feed.attemptsFor(DeliveryKey(consumer, "alice", "fp"))).isEqualTo(racers)
    finally pool.shutdownNow()
