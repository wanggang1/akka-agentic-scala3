package com.gwgs.akkaagentic.wallet.domain

import akka.javasdk.impl.serialization.JsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** US3 / Q-C — the `Wallet` state and the events round-trip through the SDK's INTERNAL component mapper
  * (`akka.javasdk.impl.serialization.JsonSerializer` — the serializer the runtime uses for the journal and
  * snapshots), in **both** directions, with no passivation needed.
  *
  * This is the ground truth behind "a wallet reconstituted from a snapshot equals a full replay": a
  * snapshot is the `Wallet` state serialized and later read back, and that is exactly what this test
  * exercises. The live reload itself is not forced here (TestKitSupport exposes no passivation hook — see
  * WalletSnapshotIntegrationTest), so this serializer round-trip is the evidence that a reload would be
  * faithful.
  */
class WalletSerializationTest:

  private val serializer = new JsonSerializer()

  @Test
  def walletStateRoundTripsBothDirections(): Unit =
    val state = Wallet(balance = 125L, open = true)
    assertThat(serializer.fromBytes(classOf[Wallet], serializer.toBytes(state))).isEqualTo(state)

  @Test
  def aClosedWalletStateRoundTrips(): Unit =
    val state = Wallet(balance = 0L, open = false)
    assertThat(serializer.fromBytes(classOf[Wallet], serializer.toBytes(state))).isEqualTo(state)

  @Test
  def everyEventRoundTripsPolymorphicallyByTypeName(): Unit =
    val events: List[WalletEvent] = List(
      new WalletEvent.Opened(100L),
      new WalletEvent.Deposited(50L, 150L),
      new WalletEvent.Withdrawn(30L, 120L),
      new WalletEvent.Closed(120L)
    )
    events.foreach(e => assertThat(serializer.fromBytes(serializer.toBytes(e))).isEqualTo(e))
