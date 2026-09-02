package app.wayfarer.core

import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.repo.ContactRepository
import app.wayfarer.core.repo.FollowBook
import app.wayfarer.core.repo.FollowSource
import app.wayfarer.core.repo.LocalFollowStore
import app.wayfarer.core.repo.PublishError
import app.wayfarer.core.repo.PublishResult
import app.wayfarer.core.repo.RelayListRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Following, by both routes.
 *
 * A kind 3 names everybody at once, so adding one person republishes the whole
 * list — which is what makes rebuilding it from a bare set of pubkeys a way to
 * destroy things the user never told this app about.
 */
class FollowingTest {
    private val clock = FakeClock(now = 1_000)
    private val transport = FakeTransport()
    private val codec = FakeCodec()
    private val directory = RelayDirectory(clock)
    private val cache = RelayListCache()
    private val router = OutboxRouter(cache, directory)
    private val relayLists = RelayListRepository(transport, codec, cache, router, directory, clock)

    private val me = pubKey(9)
    private val alice = pubKey(1)
    private val bob = pubKey(2)
    private val carol = pubKey(3)

    private fun contacts() = ContactRepository(transport, codec, router, relayLists, clock)

    private suspend fun sendable() {
        directory.approve(relay("send.example"), read = true, write = true)
    }

    @Test
    fun `following publishes a list carrying the existing follows and the new one`() =
        runTest {
            sendable()
            val contacts = contacts()
            contacts.absorb(contactEvent(me, listOf(alice, bob)), me)
            transport.published.clear()

            val result = contacts.follow(FakeSigner(me), me, carol)

            assertTrue(result is PublishResult.Success)
            val (event, _) = transport.published.single()
            assertEquals(EventKind.CONTACT_LIST, event.kind)
            assertEquals(setOf(alice, bob, carol), event.tagValues("p").mapNotNull(PubKey::parseOrNull).toSet())
        }

    @Test
    fun `unfollowing publishes a list without that person`() =
        runTest {
            sendable()
            val contacts = contacts()
            contacts.absorb(contactEvent(me, listOf(alice, bob)), me)
            transport.published.clear()

            contacts.unfollow(FakeSigner(me), me, alice)

            val (event, _) = transport.published.single()
            assertEquals(setOf(bob), event.tagValues("p").mapNotNull(PubKey::parseOrNull).toSet())
        }

    @Test
    fun `what this app does not model survives the edit`() =
        runTest {
            // A kind 3 carries petnames on its p-tags and, by long convention, a
            // relay map in its content. Rebuilding the event from pubkeys alone
            // would wipe both from every client this account is used in.
            sendable()
            val contacts = contacts()
            val previous =
                contactEvent(me, listOf(alice)).copy(
                    content = """{"wss://kept.example":{"read":true,"write":true}}""",
                    tags = listOf(listOf("p", alice.hex, "wss://hint.example", "Alice"), listOf("t", "a topic")),
                )
            contacts.absorb(previous, me)
            transport.published.clear()

            contacts.follow(FakeSigner(me), me, bob)

            val (event, _) = transport.published.single()
            assertEquals(previous.content, event.content, "the content is carried across untouched")
            assertTrue(listOf("t", "a topic") in event.tags, "a tag this app does not model must survive")
            assertTrue(
                listOf("p", alice.hex, "wss://hint.example", "Alice") in event.tags,
                "a kept follow keeps its petname rather than being replaced by a bare tag",
            )
        }

    @Test
    fun `a watch-only account cannot publish a follow`() =
        runTest {
            sendable()
            val contacts = contacts()

            val result = contacts.follow(FakeSigner(me, canSign = false), me, alice)

            assertEquals(PublishError.WatchOnlyAccount, (result as PublishResult.Failure).error)
            assertTrue(transport.published.isEmpty())
        }

    @Test
    fun `with no relay approved for sending there is nowhere to publish`() =
        runTest {
            val contacts = contacts()

            val result = contacts.follow(FakeSigner(me), me, alice)

            assertEquals(PublishError.NoApprovedWriteRelay, (result as PublishResult.Failure).error)
            assertTrue(transport.published.isEmpty())
        }

    @Test
    fun `both lists answer the one question the feed asks`() =
        runTest {
            val contacts = contacts()
            val local = LocalFollowStore(FakeKeyValueStore())
            val book = FollowBook(contacts, local)

            contacts.absorb(contactEvent(me, listOf(alice, bob)), me)
            local.load(me)
            local.add(carol)

            assertEquals(setOf(alice, bob, carol), book.now)
            assertEquals(setOf(alice, bob, carol), book.combined.first())
        }

    @Test
    fun `somebody on both lists appears once and is reported as on both`() =
        runTest {
            val contacts = contacts()
            val local = LocalFollowStore(FakeKeyValueStore())
            val book = FollowBook(contacts, local)

            contacts.absorb(contactEvent(me, listOf(alice)), me)
            local.load(me)
            local.add(alice)

            assertEquals(setOf(alice), book.now)
            assertEquals(setOf(FollowSource.Published, FollowSource.Local), book.sourcesOf(alice))
            // And somebody on neither list is on neither list.
            assertEquals(emptySet(), book.sourcesOf(carol))
        }

    @Test
    fun `an empty local list leaves the published follows alone`() =
        runTest {
            val contacts = contacts()
            val book = FollowBook(contacts, LocalFollowStore(FakeKeyValueStore()))

            contacts.absorb(contactEvent(me, listOf(alice, bob)), me)

            assertEquals(setOf(alice, bob), book.now)
        }
}
