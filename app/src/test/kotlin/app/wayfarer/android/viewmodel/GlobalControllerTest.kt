package app.wayfarer.android.viewmodel

import app.wayfarer.core.FakeBech32Codec
import app.wayfarer.core.FakeClock
import app.wayfarer.core.FakeCodec
import app.wayfarer.core.FakeKeyTool
import app.wayfarer.core.FakeKeyValueStore
import app.wayfarer.core.FakeSecretStore
import app.wayfarer.core.FakeSigner
import app.wayfarer.core.FakeTransport
import app.wayfarer.core.NostrBackend
import app.wayfarer.core.UnusedRelayInfoFetcher
import app.wayfarer.core.Wayfarer
import app.wayfarer.core.articleEvent
import app.wayfarer.core.contactEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.noteEvent
import app.wayfarer.core.pubKey
import app.wayfarer.core.relay
import app.wayfarer.core.repo.Credential
import app.wayfarer.core.repo.SignerFactory
import app.wayfarer.core.testNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Global screen's browsing rules.
 *
 * All of this is derived from caches the repositories already hold, so the tests
 * drive those caches directly rather than a relay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalControllerTest {
    private val clock = FakeClock(now = 1_000_000)
    private val transport = FakeTransport()

    private val me = pubKey(1)
    private val alice = pubKey(2)
    private val bob = pubKey(3)
    private val carol = pubKey(4)

    private val day = 24L * 60 * 60

    /** Event ids only have to be distinct and valid hex; a counter is both. */
    private var noteSeed = 0

    private suspend fun wayfarer(): Wayfarer {
        var seed = 0
        return Wayfarer.create(
            backend =
                NostrBackend(
                    codec = FakeCodec(),
                    bech32 = FakeBech32Codec,
                    keyTool = FakeKeyTool { ++seed },
                    normalizer = testNormalizer,
                    signerFactory =
                        SignerFactory { pubKey, credential ->
                            FakeSigner(pubKey, canSign = credential !is Credential.WatchOnly)
                        },
                    clock = clock,
                    relayInfoFetcher = UnusedRelayInfoFetcher,
                    transportFactory = { transport },
                ),
            settings = FakeKeyValueStore(),
            secrets = FakeSecretStore(),
            bootstrapSuggestions = emptyList(),
        )
    }

    /** Files a note straight into the feed cache, as a relay delivering it would. */
    private fun Wayfarer.note(
        author: PubKey,
        content: String,
        at: Long,
        from: RelayUrl? = null,
    ) {
        feed.absorb(noteEvent(author, content, createdAt = at, idSeed = ++noteSeed), from)
    }

    private fun Wayfarer.follows(vararg authors: Any) {
        contacts.absorb(contactEvent(me, authors.map { it as PubKey }), me)
    }

    // ---- the default mode -------------------------------------------------

    @Test
    fun `somebody who follows nobody starts in Relay mode`() =
        runTest {
            val core = wayfarer()
            val global = GlobalController(core, TestScope(testScheduler))
            runCurrent()

            // Follows mode would be an empty rotation, which reads as the app
            // failing to load rather than as a state the user is in.
            assertEquals(BrowseMode.Relay, global.state.value.mode)
        }

    @Test
    fun `somebody with follows starts in Follows mode`() =
        runTest {
            val core = wayfarer()
            core.follows(alice)
            val global = GlobalController(core, TestScope(testScheduler))
            runCurrent()

            assertEquals(BrowseMode.Follows, global.state.value.mode)
        }

    @Test
    fun `an explicit choice outlives gaining follows`() =
        runTest {
            val core = wayfarer()
            val global = GlobalController(core, TestScope(testScheduler))
            global.setMode(BrowseMode.Relay)
            core.follows(alice)
            runCurrent()

            assertEquals(BrowseMode.Relay, global.state.value.mode)
        }

    // ---- the follows rotation ---------------------------------------------

    @Test
    fun `chronological order puts whoever wrote most recently first`() =
        runTest {
            val core = wayfarer()
            core.follows(alice, bob, carol)
            core.note(alice, "old", clock.now - 5 * day)
            core.note(bob, "newest", clock.now - 1 * day)
            core.note(carol, "middle", clock.now - 3 * day)
            val global = GlobalController(core, TestScope(testScheduler))
            runCurrent()

            assertEquals(listOf(bob, carol, alice), global.state.value.rotation)
            assertEquals(bob, global.state.value.person)
        }

    @Test
    fun `the activity filter drops follows nobody has heard from`() =
        runTest {
            val core = wayfarer()
            core.follows(alice, bob)
            core.note(alice, "recent", clock.now - 2 * day)
            core.note(bob, "ancient", clock.now - 30 * day)
            val global = GlobalController(core, TestScope(testScheduler))

            global.setActivity(ActivityFilter.ActiveRecently)
            runCurrent()
            assertEquals(listOf(alice), global.state.value.rotation)
            assertEquals(1, global.state.value.hiddenByActivity)

            global.setActivity(ActivityFilter.QuietRecently)
            runCurrent()
            assertEquals(listOf(bob), global.state.value.rotation)
        }

    @Test
    fun `activity filtering is off by default`() =
        runTest {
            val core = wayfarer()
            core.follows(alice, bob)
            core.note(alice, "recent", clock.now - 2 * day)
            val global = GlobalController(core, TestScope(testScheduler))
            runCurrent()

            assertEquals(ActivityFilter.Any, global.state.value.activity)
            // Bob has posted nothing Wayfarer has seen, and is still in the list.
            assertEquals(setOf(alice, bob), global.state.value.rotation.toSet())
            assertEquals(0, global.state.value.hiddenByActivity)
        }

    @Test
    fun `a random rotation holds still until it is re-rolled`() =
        runTest {
            val core = wayfarer()
            core.follows(alice, bob, carol)
            val global = GlobalController(core, TestScope(testScheduler))
            global.setOrder(BrowseOrder.Random)
            runCurrent()
            val first = global.state.value.rotation

            // A note arriving must not reshuffle the list under the reader.
            core.note(carol, "something", clock.now)
            runCurrent()
            assertEquals(first, global.state.value.rotation)

            var reshuffled = global.state.value.rotation
            repeat(20) {
                if (reshuffled == first) {
                    global.reshuffle()
                    runCurrent()
                    reshuffled = global.state.value.rotation
                }
            }
            assertEquals(first.toSet(), reshuffled.toSet(), "re-rolling reorders, it does not drop anyone")
        }

    // ---- paging -----------------------------------------------------------

    @Test
    fun `the arrows step through people and stop at both ends`() =
        runTest {
            val core = wayfarer()
            core.follows(alice, bob)
            core.note(alice, "newer", clock.now - 1 * day)
            core.note(bob, "older", clock.now - 2 * day)
            val global = GlobalController(core, TestScope(testScheduler))
            runCurrent()

            assertEquals(alice, global.state.value.person)
            assertFalse(global.state.value.hasPrevious)
            assertTrue(global.state.value.hasNext)

            global.next()
            runCurrent()
            assertEquals(bob, global.state.value.person)
            assertFalse(global.state.value.hasNext, "there is nobody after the last person")

            global.next()
            runCurrent()
            assertEquals(bob, global.state.value.person, "stepping past the end stays put")

            global.previous()
            runCurrent()
            assertEquals(alice, global.state.value.person)
        }

    @Test
    fun `a note arriving does not move the person on screen`() =
        runTest {
            val core = wayfarer()
            core.follows(alice, bob)
            core.note(alice, "a", clock.now - 2 * day)
            core.note(bob, "b", clock.now - 3 * day)
            val global = GlobalController(core, TestScope(testScheduler))
            runCurrent()
            global.next()
            runCurrent()
            assertEquals(bob, global.state.value.person)

            // Alice posting would put her first in a chronological rotation. The
            // cursor is held by identity, so the reader stays on Bob.
            core.note(alice, "just now", clock.now)
            runCurrent()

            assertEquals(bob, global.state.value.person)
            assertEquals(listOf(alice, bob), global.state.value.rotation)
        }

    @Test
    fun `a person's notes and articles are one list, newest first`() =
        runTest {
            val core = wayfarer()
            core.follows(alice)
            core.note(alice, "note", clock.now - 2 * day)
            core.articles.absorb(articleEvent(alice, "d", "An article", createdAt = clock.now - 1 * day), null)
            val global = GlobalController(core, TestScope(testScheduler))
            runCurrent()

            val posts = global.state.value.personPosts
            assertEquals(2, posts.size)
            assertTrue(posts[0] is FeedItem.LongForm, "the newer article comes first")
            assertTrue(posts[1] is FeedItem.Post)
        }

    // ---- relay mode -------------------------------------------------------

    @Test
    fun `relay mode shows only what the chosen relay delivered`() =
        runTest {
            val core = wayfarer()
            val here = relay("here.example")
            val elsewhere = relay("elsewhere.example")
            core.relayDirectory.approve(here, read = true, write = false)
            core.relayDirectory.approve(elsewhere, read = true, write = false)
            core.note(alice, "from here", clock.now - 1 * day, from = here)
            core.note(bob, "from elsewhere", clock.now, from = elsewhere)

            val global = GlobalController(core, TestScope(testScheduler))
            global.setMode(BrowseMode.Relay)
            global.selectRelay(here)
            runCurrent()

            assertEquals(listOf(here, elsewhere).sortedBy { it.url }, global.state.value.relays)
            assertEquals(1, global.state.value.relayPosts.size)
            assertEquals("from here", (global.state.value.currentPost as FeedItem.Post).note.content)
        }

    @Test
    fun `the arrows step through one relay's posts`() =
        runTest {
            val core = wayfarer()
            val here = relay("here.example")
            core.relayDirectory.approve(here, read = true, write = false)
            core.note(alice, "newer", clock.now, from = here)
            core.note(bob, "older", clock.now - 1 * day, from = here)

            val global = GlobalController(core, TestScope(testScheduler))
            global.setMode(BrowseMode.Relay)
            runCurrent()

            assertEquals("newer", (global.state.value.currentPost as FeedItem.Post).note.content)
            assertFalse(global.state.value.hasPrevious)

            global.next()
            runCurrent()
            assertEquals("older", (global.state.value.currentPost as FeedItem.Post).note.content)
            assertFalse(global.state.value.hasNext)
            assertTrue(global.state.value.hasPrevious)
        }

    @Test
    fun `a post arriving does not move the post on screen`() =
        runTest {
            val core = wayfarer()
            val here = relay("here.example")
            core.relayDirectory.approve(here, read = true, write = false)
            core.note(alice, "first", clock.now - 2 * day, from = here)
            core.note(bob, "second", clock.now - 3 * day, from = here)

            val global = GlobalController(core, TestScope(testScheduler))
            global.setMode(BrowseMode.Relay)
            runCurrent()
            global.next()
            runCurrent()
            assertEquals("second", (global.state.value.currentPost as FeedItem.Post).note.content)

            core.note(carol, "just streamed in", clock.now, from = here)
            runCurrent()

            assertEquals(
                "second",
                (global.state.value.currentPost as FeedItem.Post).note.content,
                "a streamed arrival must not shift the post being read",
            )
        }

    @Test
    fun `revoking the relay being read falls back rather than showing nothing`() =
        runTest {
            val core = wayfarer()
            val here = relay("here.example")
            val other = relay("other.example")
            core.relayDirectory.approve(here, read = true, write = false)
            core.relayDirectory.approve(other, read = true, write = false)
            val global = GlobalController(core, TestScope(testScheduler))
            global.setMode(BrowseMode.Relay)
            global.selectRelay(here)
            runCurrent()
            assertEquals(here, global.state.value.relay)

            core.relayDirectory.forget(here)
            runCurrent()

            assertEquals(other, global.state.value.relay)
        }

    @Test
    fun `no approved relay leaves nothing to read`() =
        runTest {
            val core = wayfarer()
            val global = GlobalController(core, TestScope(testScheduler))
            global.setMode(BrowseMode.Relay)
            runCurrent()

            assertTrue(global.state.value.relays.isEmpty())
            assertNull(global.state.value.relay)
            assertNull(global.state.value.currentPost)
            assertFalse(global.state.value.hasNext)
        }

    // ---- favourites -------------------------------------------------------

    @Test
    fun `a starred relay is offered first and opened by default`() =
        runTest {
            val core = wayfarer()
            val plain = relay("aaa.example")
            val starred = relay("zzz.example")
            core.relayDirectory.approve(plain, read = true, write = false)
            core.relayDirectory.approve(starred, read = true, write = false)
            core.relayDirectory.setFavourite(starred, true)

            val global = GlobalController(core, TestScope(testScheduler))
            global.setMode(BrowseMode.Relay)
            runCurrent()

            // Alphabetically last, first anyway: a star is the user saying this
            // one matters, which outranks the fallback ordering.
            assertEquals(listOf(starred, plain), global.state.value.relays)
            assertEquals(starred, global.state.value.relay)
        }

    @Test
    fun `unstarring puts a relay back in its ordinary place`() =
        runTest {
            val core = wayfarer()
            val plain = relay("aaa.example")
            val starred = relay("zzz.example")
            core.relayDirectory.approve(plain, read = true, write = false)
            core.relayDirectory.approve(starred, read = true, write = false)
            core.relayDirectory.setFavourite(starred, true)
            val global = GlobalController(core, TestScope(testScheduler))
            global.setMode(BrowseMode.Relay)
            runCurrent()

            core.relayDirectory.setFavourite(starred, false)
            runCurrent()

            assertEquals(listOf(plain, starred), global.state.value.relays)
        }

    // ---- the activity window, now a setting -------------------------------

    @Test
    fun `the activity window comes from settings, not a constant`() =
        runTest {
            val core = wayfarer()
            core.follows(alice)
            core.note(alice, "three days ago", clock.now - 3 * day)
            val global = GlobalController(core, TestScope(testScheduler))
            global.setActivity(ActivityFilter.ActiveRecently)
            runCurrent()

            // Default is a week, so three days ago is active.
            assertEquals(listOf(alice), global.state.value.rotation)

            core.preferences.setActivityWindowDays(1)
            runCurrent()
            assertTrue(global.state.value.rotation.isEmpty(), "a one-day window makes three days ago quiet")

            core.preferences.setActivityWindowDays(30)
            runCurrent()
            assertEquals(listOf(alice), global.state.value.rotation, "a thirty-day window makes them active again")
        }

    @Test
    fun `narrowing the window moves somebody into the quiet list`() =
        runTest {
            val core = wayfarer()
            core.follows(alice)
            core.note(alice, "three days ago", clock.now - 3 * day)
            val global = GlobalController(core, TestScope(testScheduler))
            global.setActivity(ActivityFilter.QuietRecently)
            core.preferences.setActivityWindowDays(1)
            runCurrent()

            assertEquals(listOf(alice), global.state.value.rotation)
        }

    // ---- how the app answers "may I use this relay?" ----------------------

    @Test
    fun `every relay gets exactly one approval answer`() =
        runTest {
            val core = wayfarer()
            val allowed = relay("allowed.example")
            val blocked = relay("blocked.example")
            val waiting = relay("waiting.example")
            core.relayDirectory.approve(allowed, read = true, write = false)
            core.relayDirectory.deny(blocked)
            core.relayDirectory.note(
                listOf(waiting),
                app.wayfarer.core.model.DiscoveryReason(app.wayfarer.core.model.DiscoverySource.BOOTSTRAP),
            )

            val relays = RelayController(core, TestScope(testScheduler), {})
            runCurrent()
            val state = relays.state.value

            assertEquals(RelayApproval.Allowed, state.approvalOf(allowed))
            assertEquals(RelayApproval.Blocked, state.approvalOf(blocked))
            assertEquals(RelayApproval.Waiting, state.approvalOf(waiting))
            // Not an absence: a relay somebody advertises that routing has never
            // wanted is in none of the lists, and "not decided" is what the
            // profile screen has to show for it.
            assertEquals(RelayApproval.Unknown, state.approvalOf(relay("stranger.example")))
        }
}
