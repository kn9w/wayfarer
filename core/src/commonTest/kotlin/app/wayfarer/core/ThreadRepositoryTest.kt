package app.wayfarer.core

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.ThreadRef
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.repo.ThreadRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a conversation.
 *
 * Wayfarer publishes NIP-22 comments but has to *read* both conventions, because
 * a thread is whatever every other client already put there — and most of them
 * thread notes with NIP-10 kind 1 replies.
 */
class ThreadRepositoryTest {
    private val clock = FakeClock()
    private val alice = pubKey(1)
    private val bob = pubKey(2)
    private val rootNote = EventId("11".repeat(32))

    private fun repo(
        transport: FakeTransport,
        directory: RelayDirectory = RelayDirectory(clock),
        codec: FakeCodec = FakeCodec(),
    ) = ThreadRepository(transport, codec, OutboxRouter(RelayListCache(), directory), clock)

    private fun comment(
        id: String,
        author: PubKey,
        root: EventId,
        parent: EventId,
        createdAt: Long,
        text: String,
    ) = NostrEvent(
        id = EventId(id.repeat(32).take(64)),
        pubKey = author,
        createdAt = createdAt,
        kind = EventKind.COMMENT,
        tags =
            listOf(
                listOf("E", root.hex, "", author.hex),
                listOf("K", "1"),
                listOf("e", parent.hex, "", author.hex),
                listOf("k", "1"),
            ),
        content = text,
        sig = "0".repeat(128),
    )

    private fun nip10Reply(
        id: String,
        author: PubKey,
        target: EventId,
        createdAt: Long,
        text: String,
    ) = NostrEvent(
        id = EventId(id.repeat(32).take(64)),
        pubKey = author,
        createdAt = createdAt,
        kind = EventKind.TEXT_NOTE,
        tags = listOf(listOf("e", target.hex, "", "root")),
        content = text,
        sig = "0".repeat(128),
    )

    @Test
    fun `a NIP-10 reply and a NIP-22 comment land in one conversation`() {
        val threads = repo(FakeTransport())

        threads.absorb(nip10Reply("aa", alice, rootNote, createdAt = 100, text = "from another client"), relay("a.example"))
        threads.absorb(comment("bb", bob, rootNote, rootNote, createdAt = 200, text = "from wayfarer"), relay("b.example"))

        val thread = threads.threadUnder(ThreadRef.Event(rootNote))
        assertEquals(listOf("from another client", "from wayfarer"), thread.map { it.content })
        // Oldest first: a conversation is read in the order it happened.
        assertTrue(thread[0].createdAt < thread[1].createdAt)
        assertEquals(listOf(false, true), thread.map { it.isComment })
    }

    @Test
    fun `a kind 1 that is not a reply is not part of anyone's thread`() {
        val threads = repo(FakeTransport())
        val standalone =
            NostrEvent(
                id = EventId("cc".repeat(32)),
                pubKey = alice,
                createdAt = 100,
                kind = EventKind.TEXT_NOTE,
                tags = emptyList(),
                content = "just a post",
                sig = "0".repeat(128),
            )

        assertTrue(!threads.absorb(standalone, null), "a post with no reply target answers nothing")
        assertTrue(threads.threadUnder(ThreadRef.Event(rootNote)).isEmpty())
    }

    @Test
    fun `a comment that fails verification is dropped`() {
        val threads = repo(FakeTransport(), codec = FakeCodec(verifies = false))

        assertTrue(!threads.absorb(comment("dd", bob, rootNote, rootNote, 200, "forged"), null))
        assertTrue(threads.threadUnder(ThreadRef.Event(rootNote)).isEmpty())
    }

    @Test
    fun `a conversation on one root does not collect another's`() {
        val threads = repo(FakeTransport())
        val otherRoot = EventId("22".repeat(32))

        threads.absorb(comment("aa", bob, rootNote, rootNote, 100, "here"), null)
        threads.absorb(comment("bb", bob, otherRoot, otherRoot, 100, "elsewhere"), null)

        assertEquals(listOf("here"), threads.threadUnder(ThreadRef.Event(rootNote)).map { it.content })
        assertEquals(listOf("elsewhere"), threads.threadUnder(ThreadRef.Event(otherRoot)).map { it.content })
    }

    @Test
    fun `no relay is queried for a thread until one is approved`() =
        runTest {
            val transport = FakeTransport()
            val threads = repo(transport)

            assertEquals(0, threads.load(ThreadRef.Event(rootNote)))
            assertTrue(transport.fetched.isEmpty(), "a conversation is not worth breaking the permission gate for")
        }

    @Test
    fun `loading asks for each threading convention in its own filter`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("open.example"), read = true, write = false)

            repo(transport, directory).load(ThreadRef.Event(rootNote))

            val filters = transport.fetched.single().getValue(relay("open.example"))

            // Two filters, not one. NIP-01 ANDs the conditions inside a filter
            // and ORs separate filters, so "#E or #e" has to be two — asking for
            // both in one demands an event carrying both, which a NIP-10 reply
            // never is. This is the bug the previous version of this test
            // asserted *for*, by checking a single filter held both keys.
            assertEquals(2, filters.size)

            val comments = filters.single { it.kinds == listOf(EventKind.COMMENT) }
            assertEquals(listOf(rootNote.hex), comments.tags?.get("E"))

            val replies = filters.single { it.kinds == listOf(EventKind.TEXT_NOTE) }
            assertEquals(listOf(rootNote.hex), replies.tags?.get("e"))
        }

    @Test
    fun `no filter mixes an uppercase and a lowercase tag condition`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("open.example"), read = true, write = false)

            repo(transport, directory).load(ThreadRef.Event(rootNote))

            // The general form of the mistake: any single filter naming both a
            // root-scope tag and a parent tag matches only events carrying both.
            for (filter in transport.fetched.single().getValue(relay("open.example"))) {
                val names = filter.tags.orEmpty().keys
                assertTrue(
                    names.none { it.first().isUpperCase() } || names.none { it.first().isLowerCase() },
                    "a filter asking for $names would be ANDed into matching almost nothing",
                )
            }
        }

    @Test
    fun `an article thread is asked for by address alone`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("open.example"), read = true, write = false)
            val address = "30023:${alice.hex}:my-post"

            repo(transport, directory).load(ThreadRef.Address(address))

            // One filter: NIP-10 threads events, and an article is addressed by
            // kind:pubkey:d, so there is no kind 1 counterpart to ask for.
            val filter = transport.fetched.single().getValue(relay("open.example")).single()
            assertEquals(listOf(EventKind.COMMENT), filter.kinds)
            assertEquals(listOf(address), filter.tags?.get("A"))
            assertNull(filter.tags?.get("a"), "the parent tag would AND this into matching only top-level comments")
        }

    // ---- legacy replies, which is what regressed --------------------------

    @Test
    fun `a direct kind 1 reply appears under the note it answers`() {
        val threads = repo(FakeTransport())

        threads.absorb(nip10Reply("aa", alice, rootNote, createdAt = 100, text = "direct"), null)

        assertEquals(listOf("direct"), threads.threadUnder(ThreadRef.Event(rootNote)).map { it.content })
    }

    @Test
    fun `a reply to a reply still appears under the thread root`() {
        val threads = repo(FakeTransport())
        val firstReply = EventId("aa".repeat(32))

        // NIP-10 marks both: the conversation's origin, and the post being
        // answered. replyTo is the latter, so filtering a thread by replyTo
        // alone dropped this one after fetching it.
        val nested =
            NostrEvent(
                id = EventId("bb".repeat(32)),
                pubKey = bob,
                createdAt = 200,
                kind = EventKind.TEXT_NOTE,
                tags =
                    listOf(
                        listOf("e", rootNote.hex, "", "root"),
                        listOf("e", firstReply.hex, "", "reply"),
                    ),
                content = "nested",
                sig = "0".repeat(128),
            )
        threads.absorb(nested, null)

        assertEquals(listOf("nested"), threads.threadUnder(ThreadRef.Event(rootNote)).map { it.content })
    }
}
