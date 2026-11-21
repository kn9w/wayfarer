package app.wayfarer.core

import app.wayfarer.core.nostr.RelayListEntry
import app.wayfarer.core.outbox.RelayList
import app.wayfarer.core.outbox.publishersByRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayListCacheTest {
    private fun list(
        author: Int,
        vararg entries: RelayListEntry,
    ) = RelayList(pubKey(author), createdAt = 1, entries = entries.toList())

    private fun writes(host: String) = RelayListEntry(relay(host), read = false, write = true)

    private fun reads(host: String) = RelayListEntry(relay(host), read = true, write = false)

    @Test
    fun `counts every author that publishes to a relay`() {
        val hub = relay("hub.example")

        val counts =
            publishersByRelay(
                mapOf(
                    pubKey(1) to list(1, writes("hub.example")),
                    pubKey(2) to list(2, writes("hub.example")),
                    pubKey(3) to list(3, writes("niche.example")),
                ),
            )

        assertEquals(setOf(pubKey(1), pubKey(2)), counts.getValue(hub))
        assertEquals(setOf(pubKey(3)), counts.getValue(relay("niche.example")))
    }

    @Test
    fun `a relay an author only reads from is not somewhere they publish`() {
        val inbox = relay("inbox.example")

        val counts = publishersByRelay(mapOf(pubKey(1) to list(1, reads("inbox.example"))))

        // Counting it would promote a relay that would return none of this
        // author's notes, which is the opposite of what the ranking is for.
        assertTrue(inbox !in counts, "a read-only relay must not count as a publisher")
    }

    @Test
    fun `a read and write entry counts as publishing`() {
        val both = RelayListEntry(relay("both.example"), read = true, write = true)

        val counts = publishersByRelay(mapOf(pubKey(1) to list(1, both)))

        assertEquals(setOf(pubKey(1)), counts.getValue(relay("both.example")))
    }

    @Test
    fun `an author listing the same relay twice is still one author`() {
        val counts =
            publishersByRelay(
                mapOf(pubKey(1) to list(1, writes("hub.example"), writes("hub.example"))),
            )

        assertEquals(1, counts.getValue(relay("hub.example")).size)
    }

    @Test
    fun `no relay lists means nothing to rank by`() {
        assertTrue(publishersByRelay(emptyMap()).isEmpty())
    }
}
