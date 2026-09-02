package app.wayfarer.core

import app.wayfarer.core.model.EventId
import app.wayfarer.core.repo.ThreadEntry
import app.wayfarer.core.repo.threadTree
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shape of a conversation.
 *
 * [ThreadEntry] used to carry no parent at all, so a reply to a reply and a
 * reply to the post rendered identically. These tests are about the arrangement
 * only — that a reply sits under what it answers, that one whose parent never
 * arrived is still shown, and that folding one hides exactly its own subtree.
 */
class ThreadTreeTest {
    private val root = EventId("11".repeat(32))

    private fun id(seed: String) = EventId(seed.repeat(32).take(64))

    private fun entry(
        id: EventId,
        parent: EventId?,
        createdAt: Long,
    ) = ThreadEntry(
        id = id,
        author = pubKey(1),
        createdAt = createdAt,
        content = id.hex.take(4),
        seenOn = emptySet(),
        isComment = false,
        parent = parent,
    )

    @Test
    fun `a reply to the post is shallower than a reply to that reply`() {
        val first = id("aa")
        val second = id("bb")
        val nodes =
            threadTree(
                listOf(entry(first, root, 10), entry(second, first, 20)),
                root,
            )

        assertEquals(listOf(first, second), nodes.map { it.entry.id })
        assertEquals(listOf(0, 1), nodes.map { it.depth })
    }

    @Test
    fun `a parent naming the root itself is top level`() {
        val only = id("aa")
        val nodes = threadTree(listOf(entry(only, root, 10)), root)

        assertEquals(listOf(0), nodes.map { it.depth })
    }

    @Test
    fun `a null parent is top level`() {
        val only = id("aa")
        val nodes = threadTree(listOf(entry(only, null, 10)), root)

        assertEquals(listOf(0), nodes.map { it.depth })
    }

    @Test
    fun `a reply whose parent was never fetched is still shown`() {
        // A thread is asked for by its root, and a relay may return a reply while
        // withholding the reply it answers. Dropping the orphan would hide a real
        // message; it is shown against the root instead.
        val missing = id("cc")
        val orphan = id("dd")

        val nodes = threadTree(listOf(entry(orphan, missing, 10)), root)

        assertEquals(listOf(orphan), nodes.map { it.entry.id })
        assertEquals(listOf(0), nodes.map { it.depth })
    }

    @Test
    fun `an orphan reads as a top level reply, in the order it was written`() {
        // Not merely present: in its chronological place among the replies to
        // the post. Appending orphans after everything else would put an older
        // message below a newer one for no reason the reader can see.
        val missing = id("ee")
        val orphan = id("aa")
        val direct = id("bb")

        val nodes =
            threadTree(
                listOf(entry(orphan, missing, 10), entry(direct, root, 20)),
                root,
            )

        assertEquals(listOf(orphan, direct), nodes.map { it.entry.id })
        assertEquals(listOf(0, 0), nodes.map { it.depth })
    }

    @Test
    fun `siblings stay in the order they were written`() {
        val early = id("aa")
        val late = id("bb")

        val nodes =
            threadTree(
                // Offered newest first, to prove the ordering is not incidental.
                listOf(entry(late, root, 99), entry(early, root, 1)),
                root,
            )

        assertEquals(listOf(early, late), nodes.map { it.entry.id })
    }

    @Test
    fun `a reply is placed under its own parent rather than after the whole level`() {
        // Depth-first: the answer to the first reply comes before the second
        // reply, even though it was written later than both.
        val first = id("aa")
        val second = id("bb")
        val answer = id("cc")

        val nodes =
            threadTree(
                listOf(entry(first, root, 10), entry(second, root, 20), entry(answer, first, 30)),
                root,
            )

        assertEquals(listOf(first, answer, second), nodes.map { it.entry.id })
        assertEquals(listOf(0, 1, 0), nodes.map { it.depth })
    }

    @Test
    fun `descendants counts everything below an entry at any depth`() {
        val top = id("aa")
        val middle = id("bb")
        val bottom = id("cc")

        val nodes =
            threadTree(
                listOf(entry(top, root, 10), entry(middle, top, 20), entry(bottom, middle, 30)),
                root,
            )

        assertEquals(listOf(2, 1, 0), nodes.map { it.descendants })
    }

    @Test
    fun `collapsing an entry hides its whole subtree and keeps itself`() {
        val top = id("aa")
        val middle = id("bb")
        val bottom = id("cc")
        val sibling = id("dd")

        val nodes =
            threadTree(
                listOf(
                    entry(top, root, 10),
                    entry(middle, top, 20),
                    entry(bottom, middle, 30),
                    entry(sibling, root, 40),
                ),
                root,
                collapsed = setOf(top),
            )

        assertEquals(listOf(top, sibling), nodes.map { it.entry.id })
        // Still reports what it is hiding, which is what the row has to say.
        assertEquals(2, nodes.first().descendants)
    }

    @Test
    fun `collapsing a leaf hides nothing`() {
        val top = id("aa")
        val sibling = id("bb")

        val nodes =
            threadTree(
                listOf(entry(top, root, 10), entry(sibling, root, 20)),
                root,
                collapsed = setOf(top),
            )

        assertEquals(listOf(top, sibling), nodes.map { it.entry.id })
    }

    @Test
    fun `a cycle between two replies terminates and loses nothing`() {
        // Nothing in the protocol stops a relay serving a pair that name each
        // other. Where such a pair ends up hanging is arbitrary — what matters
        // is that the walk returns at all, and that neither message is dropped
        // or shown twice.
        val one = id("aa")
        val two = id("bb")

        val nodes = threadTree(listOf(entry(one, two, 10), entry(two, one, 20)), root)

        assertEquals(setOf(one, two), nodes.map { it.entry.id }.toSet())
        assertEquals(2, nodes.size, "a cycle must not duplicate a message")
    }

    @Test
    fun `folding hides a subtree rather than promoting it to the top`() {
        // The sweep that rescues unreachable entries must not rescue ones that
        // are merely folded away, or collapsing a reply redisplays its children
        // as though they were roots of their own.
        val top = id("aa")
        val child = id("bb")

        val nodes =
            threadTree(
                listOf(entry(top, root, 10), entry(child, top, 20)),
                root,
                collapsed = setOf(top),
            )

        assertEquals(listOf(top), nodes.map { it.entry.id })
    }

    @Test
    fun `an empty conversation has no nodes`() {
        assertEquals(emptyList(), threadTree(emptyList(), root))
    }
}
