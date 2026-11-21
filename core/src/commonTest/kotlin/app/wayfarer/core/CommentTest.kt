package app.wayfarer.core

import app.wayfarer.core.model.Comment
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.ThreadRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NIP-22 parsing, checked against the specification's own example events.
 *
 * The tag rows below are copied verbatim from NIP-22 rather than invented, because
 * the whole scheme turns on a distinction that is easy to get backwards — the
 * uppercase tags are the thread's root, the lowercase ones the item actually
 * being replied to — and an example written from memory would encode the same
 * misunderstanding as the parser it is meant to check.
 */
class CommentTest {
    private val author = PubKey("cd".repeat(32))

    private fun event(
        tags: List<List<String>>,
        kind: Int = EventKind.COMMENT,
        content: String = "Great blog post!",
    ) = NostrEvent(
        id = EventId("ab".repeat(32)),
        pubKey = author,
        createdAt = 1_700_000_000,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )

    // ---- the spec's examples ----------------------------------------------

    @Test
    fun `a comment on a blog post is rooted on the article's address`() {
        val address = "30023:3c9849383bdea883b0bd16fece1ed36d37e37cdde3ce43b17ea4e9192ec11289:f9347ca7"
        val articleAuthor = "3c9849383bdea883b0bd16fece1ed36d37e37cdde3ce43b17ea4e9192ec11289"

        val comment =
            Comment.fromEvent(
                event(
                    listOf(
                        listOf("A", address, "wss://example.relay"),
                        listOf("K", "30023"),
                        listOf("P", articleAuthor, "wss://example.relay"),
                        listOf("a", address, "wss://example.relay"),
                        listOf("e", "5b4fc7fed15672fefe65d2426f67197b71ccc82aa0cc8a9e94f683eb78e07651", "wss://example.relay"),
                        listOf("k", "30023"),
                        listOf("p", articleAuthor, "wss://example.relay"),
                    ),
                ),
                relay("hub.example"),
            )!!

        // The parent carries both `a` and `e`; the address is the durable one,
        // because an edited article keeps its address and changes its event id.
        assertEquals(ThreadRef.Address(address), comment.root)
        assertEquals(ThreadRef.Address(address), comment.parent)
        assertTrue(comment.isTopLevel)
        assertEquals("30023", comment.rootKind)
        assertEquals(PubKey(articleAuthor), comment.rootAuthor)
        assertEquals(setOf(relay("hub.example")), comment.seenOn)
    }

    @Test
    fun `a comment on a file is rooted on the event itself`() {
        val fileId = "768ac8720cdeb59227cf95e98b66560ef03d8bc9a90d721779e76e68fb42f5e6"
        val fileAuthor = "3721e07b079525289877c366ccab47112bdff3d1b44758ca333feb2dbbbbe5bb"

        val comment =
            Comment.fromEvent(
                event(
                    listOf(
                        listOf("E", fileId, "wss://example.relay", fileAuthor),
                        listOf("K", "1063"),
                        listOf("P", fileAuthor),
                        listOf("e", fileId, "wss://example.relay", fileAuthor),
                        listOf("k", "1063"),
                        listOf("p", fileAuthor),
                    ),
                ),
                null,
            )!!

        assertEquals(ThreadRef.Event(EventId(fileId)), comment.root)
        assertTrue(comment.isTopLevel, "a top-level comment points root and parent at the same thing")
        assertEquals("1063", comment.parentKind)
    }

    @Test
    fun `a reply to a comment keeps the root and moves the parent`() {
        val fileId = "768ac8720cdeb59227cf95e98b66560ef03d8bc9a90d721779e76e68fb42f5e6"
        val parentComment = "5c83da77af1dec6d7289834998ad7aafbd9e2191396d75ec3cc27f5a77226f36"
        val rootAuthor = "fd913cd6fa9edb8405750cd02a8bbe16e158b8676c0e69fdc27436cc4a54cc9a"
        val parentAuthor = "93ef2ebaaf9554661f33e79949007900bbc535d239a4c801c33a4d67d3e7f546"

        val comment =
            Comment.fromEvent(
                event(
                    listOf(
                        listOf("E", fileId, "wss://example.relay", rootAuthor),
                        listOf("K", "1063"),
                        listOf("P", rootAuthor),
                        listOf("e", parentComment, "wss://example.relay", parentAuthor),
                        listOf("k", "1111"),
                        listOf("p", parentAuthor),
                    ),
                ),
                null,
            )!!

        // This is the case the whole two-reference scheme exists for: one filter
        // on the root still fetches the entire conversation, and the lowercase
        // tags are what rebuild its shape.
        assertEquals(ThreadRef.Event(EventId(fileId)), comment.root)
        assertEquals(ThreadRef.Event(EventId(parentComment)), comment.parent)
        assertNotEquals(comment.root, comment.parent)
        assertTrue(!comment.isTopLevel)
        assertEquals("1063", comment.rootKind)
        assertEquals("1111", comment.parentKind, "the parent is another comment")
        assertEquals(PubKey(parentAuthor), comment.parentAuthor)
    }

    @Test
    fun `a comment on a URL has a kind that is not a number`() {
        val url = "https://abc.com/articles/1"

        val comment =
            Comment.fromEvent(
                event(
                    listOf(
                        listOf("I", url),
                        listOf("K", "web"),
                        listOf("i", url),
                        listOf("k", "web"),
                    ),
                    content = "Nice article!",
                ),
                null,
            )!!

        // K and k are strings in the spec, not event kinds. Parsing them as ints
        // would drop every comment on anything outside nostr.
        assertEquals(ThreadRef.External(url), comment.root)
        assertEquals("web", comment.rootKind)
        assertNull(comment.rootAuthor, "a URL has no author to name")
    }

    // ---- what is rejected -------------------------------------------------

    @Test
    fun `a comment without the mandatory kind tags is not readable`() {
        val id = "768ac8720cdeb59227cf95e98b66560ef03d8bc9a90d721779e76e68fb42f5e6"

        // "Tags K and k MUST be present." Without them there is no way to know
        // what the reference points at, so the comment cannot be placed.
        assertNull(Comment.fromEvent(event(listOf(listOf("E", id), listOf("e", id), listOf("k", "1"))), null))
        assertNull(Comment.fromEvent(event(listOf(listOf("E", id), listOf("K", "1"), listOf("e", id))), null))
    }

    @Test
    fun `a comment pointing at nothing is not readable`() {
        assertNull(Comment.fromEvent(event(listOf(listOf("K", "1"), listOf("k", "1"))), null))
    }

    @Test
    fun `a note is not a comment`() {
        val id = "768ac8720cdeb59227cf95e98b66560ef03d8bc9a90d721779e76e68fb42f5e6"
        val tags = listOf(listOf("E", id), listOf("K", "1"), listOf("e", id), listOf("k", "1"))

        assertNull(Comment.fromEvent(event(tags, kind = EventKind.TEXT_NOTE), null))
    }
}
