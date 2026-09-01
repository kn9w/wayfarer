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
    fun `loading asks for both threading conventions at once`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("open.example"), read = true, write = false)

            repo(transport, directory).load(ThreadRef.Event(rootNote))

            val filters = transport.fetched.single().getValue(relay("open.example")).single()
            assertEquals(listOf(EventKind.COMMENT, EventKind.TEXT_NOTE), filters.kinds)
            // Uppercase E is a NIP-22 root, lowercase e a NIP-10 target. Asking
            // for only one hides half of every conversation.
            assertEquals(listOf(rootNote.hex), filters.tags?.get("E"))
            assertEquals(listOf(rootNote.hex), filters.tags?.get("e"))
        }

    @Test
    fun `an article thread is asked for by address, not by event id`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("open.example"), read = true, write = false)
            val address = "30023:${alice.hex}:my-post"

            repo(transport, directory).load(ThreadRef.Address(address))

            val filters = transport.fetched.single().getValue(relay("open.example")).single()
            assertEquals(listOf(address), filters.tags?.get("A"))
            assertEquals(listOf(address), filters.tags?.get("a"))
        }
}
