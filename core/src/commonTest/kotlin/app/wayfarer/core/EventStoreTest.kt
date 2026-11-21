package app.wayfarer.core

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.ThreadRef
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.repo.ArticleRepository
import app.wayfarer.core.repo.EventStore
import app.wayfarer.core.repo.FeedRepository
import app.wayfarer.core.repo.RelayListRepository
import app.wayfarer.core.repo.ThreadRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Keeping the events behind the projections.
 *
 * A [app.wayfarer.core.model.Note] has no signature, no tags and no id to send,
 * so showing a reader the event behind a post or handing that event back to a
 * relay is impossible from the projection alone. These tests are about the
 * event still being there afterwards.
 */
class EventStoreTest {
    private val clock = FakeClock()
    private val alice = pubKey(1)

    private val transport = FakeTransport()
    private val codec = FakeCodec()
    private val directory = RelayDirectory(clock)
    private val cache = RelayListCache()
    private val router = OutboxRouter(cache, directory)
    private val relayLists = RelayListRepository(transport, codec, cache, router, directory, clock)

    private fun feed(events: EventStore) =
        FeedRepository(transport, codec, router, relayLists, clock, { it.hex }, null, null, events)

    @Test
    fun `an absorbed note leaves its event retrievable`() {
        val events = EventStore()
        val event = noteEvent(alice, "hello", createdAt = 100)

        feed(events).absorb(event, relay("a.example"))

        assertEquals(event, events[event.id])
    }

    @Test
    fun `an echoed event does not rebuild the map`() {
        // Relays echo each other constantly, and an id is the hash of its own
        // event, so a second copy is always identical — there is no conflict to
        // resolve. What matters is that a feed full of echoes does not churn
        // everything collecting the store: same map instance, no emission.
        val events = EventStore()
        val store = feed(events)
        val event = noteEvent(alice, "hello", createdAt = 100)

        store.absorb(event, relay("a.example"))
        val afterFirst = events.all.value

        store.absorb(event, relay("b.example"))

        assertSame(afterFirst, events.all.value)
        assertEquals(1, events.size)
        assertEquals(event, events[event.id])
    }

    @Test
    fun `an event that was never absorbed is not held`() {
        val events = EventStore()

        assertNull(events[EventId("ab".repeat(32))])
        assertEquals(0, events.size)
    }

    @Test
    fun `an absorbed article leaves its event retrievable`() {
        val events = EventStore()
        val articles = ArticleRepository(transport, codec, router, relayLists, clock, events)
        val event = articleEvent(alice, dTag = "d1", title = "A title", createdAt = 100)

        assertNotNull(articles.absorb(event, relay("a.example")))
        assertEquals(event, events[event.id])
    }

    @Test
    fun `an absorbed reply leaves its event retrievable`() {
        val events = EventStore()
        val threads = ThreadRepository(transport, codec, router, clock, null, { it.hex }, events)
        val root = EventId("11".repeat(32))
        val reply = noteEvent(alice, "answering", createdAt = 100, tags = listOf(listOf("e", root.hex, "", "root")))

        assertTrue(threads.absorb(reply, relay("a.example")))
        assertEquals(reply, events[reply.id])
        assertTrue(threads.threadUnder(ThreadRef.Event(root)).isNotEmpty())
    }

    @Test
    fun `an event the thread store rejects is not kept`() {
        // absorb is handed everything a relay returned. A kind 1 answering
        // nothing is somebody's own post, not part of this conversation, and
        // keeping it would fill the store with events nothing can show.
        val events = EventStore()
        val threads = ThreadRepository(transport, codec, router, clock, null, { it.hex }, events)
        val standalone = noteEvent(alice, "just a post", createdAt = 100)

        assertTrue(!threads.absorb(standalone, relay("a.example")))
        assertNull(events[standalone.id])
    }
}
