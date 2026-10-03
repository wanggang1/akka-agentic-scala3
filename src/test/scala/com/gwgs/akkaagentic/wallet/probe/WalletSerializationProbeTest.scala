package com.gwgs.akkaagentic.wallet.probe

import akka.javasdk.impl.serialization.JsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Phase 0 probe — Q-C (does Scala-authored SNAPSHOT state round-trip?) and a serializer-level
  * reconfirmation of Q-A/Q-B, driven through the SDK's INTERNAL component mapper directly
  * (`akka.javasdk.impl.serialization.JsonSerializer` — the same serializer the unit testkit uses, which
  * is how the enum's read-back failure was caught). This needs no passivation and no method reference, so
  * it is a pure Scala test.
  */
class WalletSerializationProbeTest:

  private val serializer = new JsonSerializer()

  /** Q-C: the Wallet STATE is a plain Scala 3 case class; snapshots (de)serialize it through this mapper. */
  @Test
  def walletStateRoundTripsThroughTheInternalMapper(): Unit =
    val state = Wallet(balance = 120L, open = true)
    val bytes = serializer.toBytes(state)
    val back = serializer.fromBytes(classOf[Wallet], bytes)
    assertThat(back).isEqualTo(state)

  /** Q-B reconfirmed at the serializer: a sealed-trait case-class event round-trips, and @TypeName
    * polymorphic read-back resolves to the concrete case. */
  @Test
  def javaSealedInterfaceEventRoundTripsPolymorphically(): Unit =
    val event: WalletEvent = new WalletEvent.Deposited(50L, 150L)
    val bytes = serializer.toBytes(event)
    // contentType carries the @TypeName discriminator (json.akka.io/deposited); the single-arg
    // fromBytes resolves the concrete subtype from it.
    val back = serializer.fromBytes(bytes)
    assertThat(back).isInstanceOf(classOf[WalletEvent.Deposited])
    assertThat(back.asInstanceOf[WalletEvent.Deposited].balance).isEqualTo(150L)

  @Test
  def allFourEventCasesRoundTrip(): Unit =
    val events: List[WalletEvent] = List(
      new WalletEvent.Opened(100L),
      new WalletEvent.Deposited(50L, 150L),
      new WalletEvent.Withdrawn(30L, 120L),
      new WalletEvent.Closed(120L)
    )
    events.foreach { e =>
      val back = serializer.fromBytes(serializer.toBytes(e))
      assertThat(back).isEqualTo(e)
    }
