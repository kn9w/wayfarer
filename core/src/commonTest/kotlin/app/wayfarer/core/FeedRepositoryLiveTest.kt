package app.wayfarer.core

import app.wayfarer.core.nostr.ReceivedEvent
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.repo.FeedRepository
import app.wayfarer.core.repo.RelayListRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live browsing subscription.
 *
 * This is the path that keeps a relay socket open for a user who follows nobody
 * — which is every new user — so it is the one that decides whether the app
 * shows "0 connected" between refreshes.
 */
// `advanceUntilIdle` is still experimental. It is the right tool here anyway:
// these tests assert on what a live subscription has delivered, which means
// letting the test scheduler run everything already queued and then looking —
// the opt-in is acknowledged rather than worked around.
@OptIn(ExperimentalCoroutinesApi::class)
class FeedRepositoryLiveTest {
    private val alice = pubKey(1)
    private val clock = FakeClock()

    private fun feed(
        transport: FakeTransport,
        directory: RelayDirectory,
    ): FeedRepository {
        val cache = RelayListCache()
        val router = OutboxRouter(cache, directory)
        val relayLists = RelayListRepository(transport, FakeCodec(), cache, router, directory, clock)
        return FeedRepository(transport, FakeCodec(), router, relayLists, clock)
    }

    @Test
    fun `an approved relay is subscribed to, not merely fetched from`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("open.example"), read = true, write = false)

            // The REQ is opened by the call itself, not by collecting: that is
            // what holds the socket up.
            feed(transport, directory).liveFromRelays(setOf(relay("open.example")), since = 100)

            assertEquals(1, transport.subscribed.size)
            assertEquals(setOf(relay("open.example")), transport.subscribed.single().keys)
            assertTrue(transport.fetched.isEmpty(), "a live subscription must not fall back to a one-shot fetch")
        }

    @Test
    fun `a relay the user has not approved is never subscribed to`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)

            feed(transport, directory).liveFromRelays(setOf(relay("stranger.example")), since = 100)

            assertTrue(transport.subscribed.isEmpty(), "no REQ may be opened against an unapproved relay")
        }

    @Test
    fun `notes arriving on the subscription reach the store`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("open.example"), read = true, write = false)
            val feed = feed(transport, directory)

            val received = mutableListOf<String>()
            val collector =
                launch {
                    feed.liveFromRelays(setOf(relay("open.example")), since = 100).collect {
                        received += it.content
                    }
                }
            advanceUntilIdle()

            transport.emit(ReceivedEvent(noteEvent(alice, "hello", createdAt = 200), relay("open.example")))
            advanceUntilIdle()

            assertEquals(listOf("hello"), received)
            assertEquals(setOf(relay("open.example")), feed.allNotes.value.values.single().seenOn)
            collector.cancel()
        }

    @Test
    fun `cancelling the collector closes the REQ`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("open.example"), read = true, write = false)
            val feed = feed(transport, directory)

            val collector = launch { feed.liveFromRelays(setOf(relay("open.example")), since = 100).collect { } }
            advanceUntilIdle()
            assertEquals(1, transport.openSubscriptions)

            collector.cancel()
            advanceUntilIdle()

            assertEquals(0, transport.openSubscriptions, "a cancelled collector must close the subscription")
        }
}
