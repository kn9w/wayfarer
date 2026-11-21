package app.wayfarer.core

import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.relay.RelayHintQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Relay hints on event tags.
 *
 * NIP-01 puts an optional relay at position 2 of an `e` or `p` tag. Nothing read
 * it before: tagValues returns position 1 by definition, and the threading
 * parsers step from 1 straight to the marker at 3, so every hint the app was
 * ever handed was discarded.
 */
class RelayHintTest {
    private val author = pubKey(1)
    private val target = EventId("11".repeat(32))

    private fun event(tags: List<List<String>>) =
        NostrEvent(
            id = EventId("ab".repeat(32)),
            pubKey = author,
            createdAt = 100,
            kind = EventKind.TEXT_NOTE,
            tags = tags,
            content = "hi",
            sig = "0".repeat(128),
        )

    @Test
    fun `a relay at position 2 of an e tag is a hint`() {
        val hints = event(listOf(listOf("e", target.hex, "wss://hinted.example", "root"))).relayHints()

        assertEquals(listOf("wss://hinted.example"), hints)
    }

    @Test
    fun `p tag hints count too`() {
        val hints = event(listOf(listOf("p", pubKey(2).hex, "wss://theirs.example"))).relayHints()

        assertEquals(listOf("wss://theirs.example"), hints)
    }

    @Test
    fun `a tag with no hint offers nothing`() {
        // Position 2 is optional, and an empty one is how a client says "no
        // suggestion" rather than naming a relay called "".
        assertTrue(event(listOf(listOf("e", target.hex))).relayHints().isEmpty())
        assertTrue(event(listOf(listOf("e", target.hex, ""))).relayHints().isEmpty())
        assertTrue(event(listOf(listOf("e", target.hex, "   "))).relayHints().isEmpty())
    }

    @Test
    fun `something that is not a websocket address is not a relay`() {
        val tags =
            listOf(
                listOf("e", target.hex, "https://example.com/not-a-relay"),
                listOf("p", pubKey(2).hex, "wat"),
            )

        assertTrue(event(tags).relayHints().isEmpty())
    }

    @Test
    fun `the same relay hinted twice is one hint`() {
        val tags =
            listOf(
                listOf("e", target.hex, "wss://same.example"),
                listOf("p", pubKey(2).hex, "wss://same.example"),
            )

        assertEquals(listOf("wss://same.example"), event(tags).relayHints())
    }

    @Test
    fun `other tags carry no hints`() {
        // An `r` tag's position 2 is a NIP-65 marker, not a relay to visit.
        assertTrue(event(listOf(listOf("r", "wss://relay.example", "write"))).relayHints().isEmpty())
    }

    // ---- the queue --------------------------------------------------------

    @Test
    fun `hints accumulate under the reason that caused them`() {
        val queue = RelayHintQueue()
        val alice = DiscoveryReason(DiscoverySource.EVENT_HINT, "a post by alice you read pointed at it")
        val bob = DiscoveryReason(DiscoverySource.EVENT_HINT, "a post by bob you read pointed at it")

        queue.offer(listOf("wss://a.example"), alice)
        queue.offer(listOf("wss://b.example"), alice)
        queue.offer(listOf("wss://a.example"), bob)

        val drained = queue.drain()
        // The same relay named by two people is two reasons to want it, and the
        // relay screen shows both.
        assertEquals(setOf("wss://a.example", "wss://b.example"), drained[alice])
        assertEquals(setOf("wss://a.example"), drained[bob])
    }

    @Test
    fun `draining empties the queue`() {
        val queue = RelayHintQueue()
        queue.offer(listOf("wss://a.example"), DiscoveryReason(DiscoverySource.EVENT_HINT, "x"))

        assertEquals(1, queue.drain().size)
        assertTrue(queue.drain().isEmpty(), "a hint recorded once must not be recorded again")
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `offering nothing does not create an empty reason`() {
        val queue = RelayHintQueue()

        queue.offer(emptyList(), DiscoveryReason(DiscoverySource.EVENT_HINT, "x"))

        assertTrue(queue.isEmpty)
    }
}
