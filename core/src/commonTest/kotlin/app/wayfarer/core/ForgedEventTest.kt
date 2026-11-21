package app.wayfarer.core

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.repo.ContactRepository
import app.wayfarer.core.repo.ProfileRepository
import app.wayfarer.core.repo.RelayListRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a relay may not talk this app into believing.
 *
 * Every event arriving from a relay is somebody's claim until its signature says
 * otherwise, and a relay can put any pubkey it likes in the author field. The
 * feed, article and thread repositories have always verified. These three did
 * not, and they are the ones whose contents change what the app *does* rather
 * than what it shows: where to fetch an author from, which relays get offered
 * for approval, and — through the follow list — what the app will sign next.
 *
 * The fake codec's [FakeCodec.verify] answers the `verifies` flag, so a "forged"
 * event here is one absorbed by a repository built with `FakeCodec(verifies =
 * false)`. That stands in for a real signature failure without needing secp256k1
 * in a core test; the real verification is covered against Quartz's own crypto.
 */
class ForgedEventTest {
    private val clock = FakeClock(now = 1_000)
    private val transport = FakeTransport()
    private val directory = RelayDirectory(clock)
    private val cache = RelayListCache()
    private val router = OutboxRouter(cache, directory)

    private val me = pubKey(9)
    private val alice = pubKey(1)
    private val mallory = pubKey(7)

    private fun relayLists(verifies: Boolean) =
        RelayListRepository(transport, FakeCodec(verifies), cache, router, directory, clock)

    private fun relayListEvent(
        author: PubKey,
        relays: List<String>,
        createdAt: Long = 100,
    ) = NostrEvent(
        id = EventId("ab".repeat(32)),
        pubKey = author,
        createdAt = createdAt,
        kind = EventKind.RELAY_LIST,
        tags = relays.map { listOf("r", it) },
        content = "",
        sig = "0".repeat(128),
    )

    private fun profileEvent(
        author: PubKey,
        name: String,
        createdAt: Long = 100,
    ) = NostrEvent(
        id = EventId("cd".repeat(32)),
        pubKey = author,
        createdAt = createdAt,
        kind = EventKind.METADATA,
        tags = emptyList(),
        content = name,
        sig = "0".repeat(128),
    )

    @Test
    fun `a relay list that does not verify is not cached`() =
        runTest {
            val absorbed = relayLists(verifies = false).absorb(relayListEvent(alice, listOf("wss://evil.example/")))

            assertNull(absorbed)
            assertNull(cache[alice])
        }

    @Test
    fun `a relay list that verifies is cached`() =
        runTest {
            val absorbed = relayLists(verifies = true).absorb(relayListEvent(alice, listOf("wss://good.example/")))

            assertEquals(alice, absorbed?.author)
            assertEquals(1, cache[alice]?.entries?.size)
        }

    @Test
    fun `a forged relay list cannot put its relays into the approval queue`() =
        runTest {
            val repo = relayLists(verifies = false)
            val forged = repo.absorb(relayListEvent(alice, listOf("wss://evil.example/")))

            // Nothing to offer, because nothing was absorbed — which is the point:
            // a pending entry reading "relay list of npub1…" is a claim the user is
            // asked to trust, so it must come from an event that actually verified.
            assertNull(forged)
            assertTrue(directory.pending.isEmpty())
        }

    @Test
    fun `a profile that does not verify is not shown`() =
        runTest {
            val profiles = ProfileRepository(transport, FakeCodec(verifies = false), router, relayLists(true), clock)

            profiles.absorb(profileEvent(alice, "Impersonated"))

            assertNull(profiles[alice])
        }

    @Test
    fun `a forged profile is refused however new it claims to be`() =
        runTest {
            val profiles = ProfileRepository(transport, FakeCodec(verifies = false), router, relayLists(true), clock)

            // A far-future created_at is free to a forger, so newest-wins must not
            // be the only thing standing between a relay and an impersonation.
            profiles.absorb(profileEvent(alice, "Impersonated", createdAt = 9_999_999))

            assertNull(profiles[alice])
        }

    @Test
    fun `a follow list that does not verify is ignored`() =
        runTest {
            val contacts = ContactRepository(transport, FakeCodec(verifies = false), router, relayLists(true), clock)

            contacts.absorb(contactEvent(me, listOf(mallory), createdAt = 9_999), me)

            assertTrue(contacts.follows.value.isEmpty())
            assertFalse(contacts.loaded)
        }

    @Test
    fun `a forged follow list cannot become the base of the next published one`() =
        runTest {
            directory.approve(relay("send.example"), read = true, write = true)
            val contacts = ContactRepository(transport, FakeCodec(verifies = false), router, relayLists(true), clock)

            // The attack this closes: absorb a forged kind 3, then let the user
            // follow somebody. publish() rebuilds over the previous event to keep
            // petnames and unknown tags, so a forgery accepted here would be signed
            // with the user's own key on the next follow.
            contacts.absorb(contactEvent(me, listOf(mallory), createdAt = 9_999), me)
            contacts.follow(FakeSigner(me), me, alice)

            val (published, _) = transport.published.single()
            val taggedPubKeys = published.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }
            assertEquals(listOf(alice.hex), taggedPubKeys)
        }
}
