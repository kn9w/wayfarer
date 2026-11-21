package app.wayfarer.core

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PaymentTarget
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.repo.PaymentRepository
import app.wayfarer.core.repo.PublishResult
import app.wayfarer.core.repo.RelayListRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** NIP-A3: `["payto", "<type>", "<address>"]` on a kind 10133. */
class PaymentTargetTest {
    private val alice = pubKey(1)
    private val clock = FakeClock()

    private fun repo(
        transport: FakeTransport = FakeTransport(),
        codec: FakeCodec = FakeCodec(),
        directory: RelayDirectory = RelayDirectory(clock),
    ): PaymentRepository {
        val cache = RelayListCache()
        val router = OutboxRouter(cache, directory)
        val relayLists = RelayListRepository(transport, codec, cache, router, directory, clock)
        return PaymentRepository(transport, codec, router, relayLists, clock)
    }

    @Test
    fun `payto tags are read in the order they appear`() {
        val event =
            paymentEvent(
                alice,
                "bitcoin" to "bc1qxq66e0t8d7ugdecwnmv58e90tpry23nc84pg9k",
                "lightning" to "alice@example.com",
            )

        assertEquals(
            listOf(
                PaymentTarget("bitcoin", "bc1qxq66e0t8d7ugdecwnmv58e90tpry23nc84pg9k"),
                PaymentTarget("lightning", "alice@example.com"),
            ),
            PaymentTarget.fromEvent(event),
        )
    }

    @Test
    fun `a type this app has never heard of is kept, because the NIP says one may appear`() {
        val event = paymentEvent(alice, "unknowntype" to "l7tbta5b9xze6ckkfc99uohzxd009b0r")

        assertEquals(listOf(PaymentTarget("unknowntype", "l7tbta5b9xze6ckkfc99uohzxd009b0r")), PaymentTarget.fromEvent(event))
    }

    @Test
    fun `a type is lowercased, because the tag is defined lowercase`() {
        assertEquals(listOf(PaymentTarget("bitcoin", "bc1q")), PaymentTarget.fromEvent(paymentEvent(alice, "BitCoin" to "bc1q")))
    }

    @Test
    fun `half a target is not shown as somewhere to send money`() {
        val event =
            NostrEvent(
                id = EventId("a3".repeat(32)),
                pubKey = alice,
                createdAt = 1,
                kind = EventKind.PAYMENT_TARGETS,
                tags =
                    listOf(
                        listOf("payto"),
                        listOf("payto", "bitcoin"),
                        listOf("payto", "", "bc1q"),
                        listOf("payto", "bit coin", "bc1q"),
                        listOf("payto", "with/slash", "bc1q"),
                        listOf("payto", "bitcoin", "bc1qreal"),
                    ),
                content = "",
                sig = "0".repeat(128),
            )

        assertEquals(listOf(PaymentTarget("bitcoin", "bc1qreal")), PaymentTarget.fromEvent(event))
    }

    @Test
    fun `a type with its own uri scheme gets one, and everything else falls back to payto`() {
        assertEquals("bitcoin:bc1q", PaymentTarget("bitcoin", "bc1q").uri())
        assertEquals("payto://nano/nano_1d", PaymentTarget("nano", "nano_1d").uri())
        assertEquals("payto://unknowntype/l7tb", PaymentTarget("unknowntype", "l7tb").uri())
    }

    @Test
    fun `tags round-trip through the event they are published as`() {
        val targets = listOf(PaymentTarget("lightning", "alice@example.com"), PaymentTarget("monero", "4Aabc"))

        val event = paymentEvent(alice, *targets.map { it.type to it.address }.toTypedArray())

        assertEquals(PaymentTarget.toTags(targets), event.tags)
        assertEquals(targets, PaymentTarget.fromEvent(event))
    }

    // ---- the repository ----------------------------------------------------

    @Test
    fun `an unverified kind 10133 is dropped rather than shown on somebody's profile`() {
        val payments = repo(codec = FakeCodec(verifies = false))

        payments.absorb(paymentEvent(alice, "bitcoin" to "bc1qattacker"))

        assertTrue(payments[alice].isEmpty())
    }

    @Test
    fun `an older list arriving late does not overwrite the newer one`() {
        val payments = repo()
        payments.absorb(paymentEvent(alice, "bitcoin" to "bc1qnew", createdAt = 200))

        payments.absorb(paymentEvent(alice, "bitcoin" to "bc1qold", createdAt = 100))

        assertEquals(listOf(PaymentTarget("bitcoin", "bc1qnew")), payments[alice])
    }

    @Test
    fun `publishing an empty list withdraws the addresses rather than doing nothing`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("write.example"), read = true, write = true)
            val payments = repo(transport = transport, directory = directory)
            payments.absorb(paymentEvent(alice, "bitcoin" to "bc1qold", createdAt = 100))
            clock.now = 500

            val result = payments.publish(FakeSigner(alice), alice, emptyList())

            assertIs<PublishResult.Success>(result)
            // A replaceable event, so the empty one is what other clients keep.
            assertEquals(EventKind.PAYMENT_TARGETS, transport.published.single().first.kind)
            assertTrue(transport.published.single().first.tags.isEmpty())
            assertTrue(payments[alice].isEmpty())
        }

    @Test
    fun `a watch-only account cannot publish payment addresses`() =
        runTest {
            val payments = repo()

            val result = payments.publish(FakeSigner(alice, canSign = false), alice, listOf(PaymentTarget("bitcoin", "bc1q")))

            assertIs<PublishResult.Failure>(result)
        }
}
