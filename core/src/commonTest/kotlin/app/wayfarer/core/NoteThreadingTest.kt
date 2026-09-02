package app.wayfarer.core

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * NIP-10 threading: which event a reply answers, and which it belongs under.
 *
 * The two are different, and conflating them is what made nested replies vanish
 * from conversations — they were fetched, then filtered out because their
 * immediate parent is not the thread's root.
 */
class NoteThreadingTest {
    private val author = pubKey(1)
    private val root = EventId("11".repeat(32))
    private val parent = EventId("22".repeat(32))

    private fun note(tags: List<List<String>>) =
        Note.fromEvent(
            NostrEvent(
                id = EventId("ab".repeat(32)),
                pubKey = author,
                createdAt = 100,
                kind = EventKind.TEXT_NOTE,
                tags = tags,
                content = "hello",
                sig = "0".repeat(128),
            ),
            null,
        )!!

    @Test
    fun `a post that answers nothing has neither`() {
        val plain = note(emptyList())

        assertNull(plain.replyTo)
        assertNull(plain.threadRoot)
    }

    @Test
    fun `a direct reply is its own thread's root reference`() {
        // A top-level reply carries one e-tag marked root and no separate
        // parent, so the post it answers and the thread it starts under are the
        // same event.
        val direct = note(listOf(listOf("e", root.hex, "", "root")))

        assertEquals(root, direct.replyTo)
        assertEquals(root, direct.threadRoot)
    }

    @Test
    fun `a nested reply names its parent and its thread separately`() {
        val nested =
            note(
                listOf(
                    listOf("e", root.hex, "", "root"),
                    listOf("e", parent.hex, "", "reply"),
                ),
            )

        assertEquals(parent, nested.replyTo, "replyTo is what is being answered")
        assertEquals(root, nested.threadRoot, "threadRoot is where the conversation began")
    }

    @Test
    fun `the deprecated positional form puts the root first and the parent last`() {
        // NIP-10's older scheme: no markers, position carries the meaning.
        val positional = note(listOf(listOf("e", root.hex), listOf("e", parent.hex)))

        assertEquals(parent, positional.replyTo)
        assertEquals(root, positional.threadRoot)
    }

    @Test
    fun `a single unmarked e-tag is both`() {
        val single = note(listOf(listOf("e", root.hex)))

        assertEquals(root, single.replyTo)
        assertEquals(root, single.threadRoot)
    }
}
