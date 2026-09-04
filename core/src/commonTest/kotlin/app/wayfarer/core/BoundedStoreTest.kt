package app.wayfarer.core

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.repo.EventStore
import app.wayfarer.core.util.StoreLimits
import app.wayfarer.core.util.plusBounded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The in-memory stores must not grow forever.
 *
 * Every store in the app is filled from the network by a subscription that stays
 * open for as long as the app does, so "how many entries may this hold" was a
 * question a *relay* answered before these caps existed. Pinned as tests because
 * the failure mode is invisible until it is fatal: nothing misbehaves, the app
 * simply uses more memory the longer it runs and eventually dies — on a
 * timescale no test session and no hand-run of the app ever reaches.
 */
class BoundedStoreTest {
    /** Distinct by construction, unlike anything derived from a short seed. */
    private fun event(index: Int) =
        NostrEvent(
            id = EventId(index.toString(16).padStart(64, '0')),
            pubKey = pubKey(1),
            createdAt = 1_700_000_000L + index,
            kind = EventKind.TEXT_NOTE,
            tags = emptyList(),
            content = "note $index",
            sig = "0".repeat(128),
        )

    @Test
    fun `a map under its cap keeps everything`() {
        var map = emptyMap<Int, String>()
        repeat(10) { map = map.plusBounded(it, "v$it", max = 16) }

        assertEquals(10, map.size)
        assertEquals("v0", map[0])
    }

    @Test
    fun `a map at its cap drops the oldest entry`() {
        var map = emptyMap<Int, String>()
        repeat(5) { map = map.plusBounded(it, "v$it", max = 3) }

        assertEquals(3, map.size)
        assertNull(map[0], "the first entry should have been evicted")
        assertEquals(setOf(2, 3, 4), map.keys)
    }

    @Test
    fun `re-putting a key does not renew its place in the queue`() {
        // The rule that matters for a feed: relays echo each other constantly, so
        // a note that keeps being re-delivered must not hold its slot forever and
        // push genuinely newer events out.
        var map = emptyMap<Int, String>()
        map = map.plusBounded(1, "first", max = 3)
        map = map.plusBounded(2, "second", max = 3)
        map = map.plusBounded(1, "first again", max = 3)
        map = map.plusBounded(3, "third", max = 3)
        map = map.plusBounded(4, "fourth", max = 3)

        assertNull(map[1], "key 1 was the oldest insertion and should still go first")
        assertEquals(setOf(2, 3, 4), map.keys)
    }

    @Test
    fun `the newest entry always survives`() {
        var map = emptyMap<Int, String>()
        repeat(100) { map = map.plusBounded(it, "v$it", max = 1) }

        assertEquals(1, map.size)
        assertEquals("v99", map[99])
    }

    @Test
    fun `the event store stops growing at its limit`() {
        val store = EventStore()

        // Past the cap, so eviction is exercised rather than just the boundary.
        repeat(StoreLimits.EVENTS + 50) { store.put(event(it)) }

        assertEquals(StoreLimits.EVENTS, store.size)
    }

    @Test
    fun `the event store keeps the newest event and drops the earliest`() {
        val store = EventStore()
        repeat(StoreLimits.EVENTS + 1) { store.put(event(it)) }

        assertNull(store[event(0).id], "the oldest event should have been evicted")
        assertNotNull(store[event(StoreLimits.EVENTS).id], "the newest event must always be retrievable")
    }

    @Test
    fun `an event echoed by every relay is stored once`() {
        val store = EventStore()

        repeat(50) { store.put(event(7)) }

        assertEquals(1, store.size)
        assertNotNull(store[event(7).id])
    }
}
