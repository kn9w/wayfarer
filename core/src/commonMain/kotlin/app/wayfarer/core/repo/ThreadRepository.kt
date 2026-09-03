package app.wayfarer.core.repo

import app.wayfarer.core.model.Comment
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.ThreadRef
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.nostr.ReqFilter
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.relay.RelayHintQueue
import app.wayfarer.core.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Conversations: what has been said under a note or an article.
 *
 * Wayfarer *reads* both threading conventions and *writes* one. Incoming, a
 * thread is whatever other clients put there — NIP-10 kind 1 replies, which is
 * how every mainstream client threads notes, and NIP-22 kind 1111 comments,
 * which is how articles and everything non-kind-1 are commented on. Outgoing,
 * this app publishes kind 1111 for both, which is a deliberate choice: it is
 * what NIP-22 allows and it keeps one code path, at the cost of replies to notes
 * not threading in clients that only look for kind 1.
 *
 * Kept apart from [FeedRepository] because a thread is fetched on demand, for
 * one root, rather than streamed — and because mixing replies into the note
 * store is what made replies look like top-level posts in the first place.
 */
class ThreadRepository(
    private val transport: RelayTransport,
    private val codec: NostrCodec,
    private val router: OutboxRouter,
    private val clock: Clock,
    private val hints: RelayHintQueue? = null,
    private val describe: (PubKey) -> String = { it.abbreviated() },
    private val events: EventStore? = null,
) {
    private val comments = MutableStateFlow<Map<EventId, Comment>>(emptyMap())
    private val replies = MutableStateFlow<Map<EventId, Note>>(emptyMap())
    private val roots = MutableStateFlow<Map<EventId, Note>>(emptyMap())

    val allComments: StateFlow<Map<EventId, Comment>> = comments.asStateFlow()

    val allReplies: StateFlow<Map<EventId, Note>> = replies.asStateFlow()

    /**
     * The posts conversations hang from, when they had to be fetched.
     *
     * A thread opened from a reply — "see the conversation" — is a thread whose
     * root the reader has very often never seen: the reply arrived in a feed and
     * the post it answers did not. Everything under the root was fetched and
     * shown, and the post itself was silently missing, so a conversation opened
     * that way began in the middle.
     *
     * Kept apart from [replies] because a root is not a reply — it answers
     * nothing, and [absorb] rejects it for exactly that reason — and apart from
     * the feed because it was never part of one.
     */
    val threadRoots: StateFlow<Map<EventId, Note>> = roots.asStateFlow()

    /**
     * Everything written under [root], oldest first.
     *
     * Forwards, unlike the feed: a conversation is read in the order it
     * happened, and the reply that prompted the next one has to come first.
     */
    fun threadUnder(root: ThreadRef): List<ThreadEntry> {
        val fromComments =
            comments.value.values
                .filter { it.root == root }
                .map {
                    ThreadEntry(
                        it.id,
                        it.author,
                        it.createdAt,
                        it.content,
                        it.seenOn,
                        isComment = true,
                        // NIP-22 keeps the root in uppercase tags and what is
                        // actually being answered in lowercase ones. Only an
                        // event parent can be placed in a thread: an address or
                        // an external reference is the root's own identity, not
                        // another entry in this list.
                        parent = (it.parent as? ThreadRef.Event)?.id,
                    )
                }
        val fromReplies =
            replies.value.values
                // threadRoot as well as replyTo: a reply to a reply names its
                // parent in replyTo, so matching on that alone fetched nested
                // replies and then dropped them before the screen.
                .filter { root is ThreadRef.Event && (it.threadRoot == root.id || it.replyTo == root.id) }
                .map {
                    ThreadEntry(
                        it.id,
                        it.author,
                        it.createdAt,
                        it.content,
                        it.seenOn,
                        isComment = false,
                        // NIP-10: replyTo is the immediate parent, threadRoot the
                        // conversation. The pair is what the shape is built from.
                        parent = it.replyTo,
                    )
                }
        return (fromComments + fromReplies).sortedBy { it.createdAt }
    }

    /**
     * Fetches the thread under [root], and the post it hangs from.
     *
     * Two filters for the replies, because the two conventions live in different
     * tags: kind 1111 comments carry the root in an uppercase `E`/`A`, and kind 1
     * replies carry it in a lowercase `e`. Asking for only one would silently
     * hide half of every conversation. A third asks for the root event by id, so
     * a conversation opened from one of its replies has a beginning.
     */
    suspend fun load(root: ThreadRef): Int {
        val plan =
            router.planFor(
                relays = router.discoveryRelays(),
                filters = filtersFor(root),
                reason = DiscoveryReason(DiscoverySource.USER_ENTERED, "you opened a conversation"),
            )
        if (plan.isEmpty()) return 0

        var absorbed = 0
        for (received in transport.fetch(plan)) {
            // The root itself comes back on the third filter below, and it is
            // not a reply to anything — so absorb refuses it, correctly, and it
            // is filed here instead.
            if (root is ThreadRef.Event && received.event.id == root.id) {
                if (absorbRoot(received.event, received.relay)) absorbed++
                continue
            }
            if (absorb(received.event, received.relay)) absorbed++
        }
        return absorbed
    }

    /** Files the post a conversation hangs from. Verified like everything else. */
    private fun absorbRoot(
        event: NostrEvent,
        relay: RelayUrl?,
    ): Boolean {
        if (!codec.verify(event)) return false
        val note = Note.fromEvent(event, relay) ?: return false
        events?.put(event)
        val existing = roots.value[note.id]
        val merged = existing?.mergeSeenOn(note.seenOn) ?: note
        if (merged !== existing) roots.value = roots.value + (merged.id to merged)
        return existing == null
    }

    /**
     * One filter per convention, never one filter for both — plus the root.
     *
     * NIP-01: conditions inside a filter are ANDed, separate filters are ORed.
     * Asking for `#E` and `#e` together — which this did — demands an event
     * carrying both tags pointed at the root, and almost nothing carries both: a
     * NIP-10 reply has only `e`, and a nested NIP-22 comment has `E` for the
     * root and `e` for its parent. Only a top-level comment, where the two
     * coincide, ever matched, so every legacy reply was invisible.
     *
     * Uppercase gathers the NIP-22 side at any depth, because the root scope
     * does not move as a thread deepens. Lowercase `e` gathers kind 1 replies,
     * which name the thread root in an e-tag whether direct or nested.
     */
    private fun filtersFor(root: ThreadRef): List<ReqFilter> =
        when (root) {
            is ThreadRef.Event ->
                listOf(
                    ReqFilter(kinds = listOf(EventKind.COMMENT), tags = mapOf("E" to listOf(root.id.hex))),
                    ReqFilter(kinds = listOf(EventKind.TEXT_NOTE), tags = mapOf("e" to listOf(root.id.hex))),
                    // And the post itself. Every filter above asks for things
                    // that *point at* the root, which is everything in the
                    // conversation except the one post it is about — so a thread
                    // opened from a reply used to render its answers under a gap.
                    ReqFilter(ids = listOf(root.id.hex)),
                )
            // No kind 1 counterpart: NIP-10 threads events, and an article is
            // addressed by `kind:pubkey:d` rather than by any single event.
            is ThreadRef.Address ->
                listOf(ReqFilter(kinds = listOf(EventKind.COMMENT), tags = mapOf("A" to listOf(root.address))))
            is ThreadRef.External ->
                listOf(ReqFilter(kinds = listOf(EventKind.COMMENT), tags = mapOf("I" to listOf(root.value))))
        }

    /**
     * Files a fetched event, returning whether it was one this store wanted.
     *
     * Signatures are verified here for the same reason [FeedRepository.absorb]
     * verifies them: a client that renders unverified events is a client whose
     * conversations a relay can forge.
     */
    fun absorb(
        event: NostrEvent,
        relay: RelayUrl?,
    ): Boolean {
        if (!codec.verify(event)) return false

        hints?.offer(
            event.relayHints(),
            DiscoveryReason(
                DiscoverySource.EVENT_HINT,
                "a reply by ${describe(event.pubKey)} in a conversation you opened pointed at it",
            ),
        )

        Comment.fromEvent(event, relay)?.let { incoming ->
            // Only once this is known to be part of a conversation: absorb is
            // handed everything a relay returned, and storing what it rejects
            // would fill the store with events nothing can show.
            events?.put(event)
            val existing = comments.value[incoming.id]
            val merged = existing?.mergeSeenOn(incoming.seenOn) ?: incoming
            if (merged !== existing) comments.value = comments.value + (merged.id to merged)
            return true
        }

        // A kind 1 with no reply target is somebody's own post that happens to
        // mention this event, not a reply to it.
        val reply = Note.fromEvent(event, relay)?.takeIf { it.replyTo != null } ?: return false
        events?.put(event)
        val existing = replies.value[reply.id]
        val merged = existing?.mergeSeenOn(reply.seenOn) ?: reply
        if (merged !== existing) replies.value = replies.value + (merged.id to merged)
        return true
    }

    /**
     * Publishes a comment.
     *
     * The uppercase tags name the root — what the whole thread is about — and
     * the lowercase tags the item actually being replied to. On a top-level
     * comment they are the same thing; deeper in a thread they diverge, and it
     * is that pair which lets a reader fetch the conversation by its root and
     * still rebuild its shape.
     */
    suspend fun comment(
        signer: EventSigner,
        author: PubKey,
        root: ThreadRef,
        rootKind: String,
        rootAuthor: PubKey?,
        parent: ThreadRef,
        parentKind: String,
        parentAuthor: PubKey?,
        content: String,
    ): PublishResult {
        if (!signer.canSign) return PublishResult.Failure(PublishError.WatchOnlyAccount)

        val tags = mutableListOf<List<String>>()
        tags += refTag(root, uppercase = true, author = rootAuthor)
        tags += listOf("K", rootKind)
        rootAuthor?.let { tags += listOf("P", it.hex) }
        tags += refTag(parent, uppercase = false, author = parentAuthor)
        tags += listOf("k", parentKind)
        parentAuthor?.let { tags += listOf("p", it.hex) }

        val event =
            signer.sign(
                UnsignedEvent(
                    kind = EventKind.COMMENT,
                    content = content,
                    tags = tags,
                    createdAt = clock.nowSeconds(),
                ),
            )

        // The people being answered are mentions, so the comment is routed to
        // their read relays and actually reaches them.
        val mentions = setOfNotNull(rootAuthor, parentAuthor) - author
        val plan = router.publishPlanFor(author, mentions)
        if (plan.isEmpty) return PublishResult.Failure(PublishError.NoApprovedWriteRelay)

        val outcomes = transport.publish(event, plan.relays)
        absorb(event, null)

        val report = PublishReport(event, plan, outcomes)
        return if (report.anyAccepted) PublishResult.Success(report) else PublishResult.Failure(PublishError.Rejected(report))
    }

    private fun refTag(
        ref: ThreadRef,
        uppercase: Boolean,
        author: PubKey?,
    ): List<String> {
        fun name(letter: String) = if (uppercase) letter.uppercase() else letter
        return when (ref) {
            // The empty third element is the relay hint position, which NIP-22's
            // own examples occupy and this app has nothing trustworthy to put in.
            is ThreadRef.Event -> listOf(name("e"), ref.id.hex, "", author?.hex.orEmpty())
            is ThreadRef.Address -> listOf(name("a"), ref.address, "", author?.hex.orEmpty())
            is ThreadRef.External -> listOf(name("i"), ref.value)
        }
    }
}

/**
 * One thing said under a root.
 *
 * A NIP-22 comment and a NIP-10 reply differ in how they point at what they
 * answer, and not at all in what a reader sees, so both arrive here. [isComment]
 * is kept only so the UI can say which convention a reply came by.
 *
 * [parent] is what makes a conversation a shape rather than a list. This class
 * used to be flat on purpose, which meant a reply to a reply was indistinguishable
 * from a reply to the post — the information was parsed one layer down, in
 * [Comment.parent] and [Note.replyTo], and then dropped on the way to the screen.
 * Null means this answers the root itself.
 */
data class ThreadEntry(
    val id: EventId,
    val author: PubKey,
    val createdAt: Long,
    val content: String,
    val seenOn: Set<RelayUrl>,
    val isComment: Boolean,
    val parent: EventId? = null,
)

/** One entry, placed: how deep it sits and how much hangs below it. */
data class ThreadNode(
    val entry: ThreadEntry,
    val depth: Int,
    /** Everything below this entry, at any depth — what collapsing it would hide. */
    val descendants: Int,
)

/**
 * Arranges a conversation into the shape it was written in.
 *
 * Depth-first and oldest-first, so a reply sits under what it answers and reads
 * in the order it happened — the same order [ThreadRepository.threadUnder]
 * already returns, applied per level.
 *
 * An entry whose parent is not in [entries] is treated as a reply to the root
 * rather than dropped. A thread is fetched by its root, and a relay is free to
 * return a reply while withholding its parent, so orphans are normal; hiding
 * them would silently lose the visible half of a conversation.
 *
 * [collapsed] ids keep their own row and lose their subtree, which is what
 * [ThreadNode.descendants] is there to report.
 */
fun threadTree(
    entries: List<ThreadEntry>,
    root: EventId?,
    collapsed: Set<EventId> = emptySet(),
): List<ThreadNode> {
    if (entries.isEmpty()) return emptyList()

    val ordered = entries.sortedBy { it.createdAt }
    val present = ordered.mapTo(mutableSetOf()) { it.id }
    val children = mutableMapOf<EventId?, MutableList<ThreadEntry>>()

    for (entry in ordered) {
        // Null parent, the root itself, or a parent that never arrived: all of
        // them answer the root as far as a reader can tell.
        val under = entry.parent?.takeIf { it != root && it in present }
        children.getOrPut(under) { mutableListOf() } += entry
    }

    // Two replies naming each other is a shape no relay is obliged to rule out,
    // and it belongs to no root, so both the count and the walk carry their own
    // visited set rather than trusting the links to terminate.
    fun descendantsOf(
        id: EventId,
        counted: MutableSet<EventId>,
    ): Int {
        var total = 0
        for (child in children[id].orEmpty()) {
            if (!counted.add(child.id)) continue
            total += 1 + descendantsOf(child.id, counted)
        }
        return total
    }

    // Which entries hang off the root at all, ignoring what is folded. Folding
    // must hide a subtree, not push it back to the top: without this pass the
    // sweep below cannot tell "deliberately hidden" from "unreachable", and
    // collapsing a reply re-displayed its children as though they were roots.
    val reachable = mutableSetOf<EventId>()

    fun mark(parent: EventId?) {
        for (entry in children[parent].orEmpty()) {
            if (reachable.add(entry.id)) mark(entry.id)
        }
    }

    mark(null)

    val placed = mutableSetOf<EventId>()
    val out = mutableListOf<ThreadNode>()

    fun walk(
        parent: EventId?,
        depth: Int,
    ) {
        for (entry in children[parent].orEmpty()) {
            if (!placed.add(entry.id)) continue
            out += ThreadNode(entry, depth, descendantsOf(entry.id, mutableSetOf(entry.id)))
            if (entry.id !in collapsed) walk(entry.id, depth + 1)
        }
    }

    walk(null, 0)

    // What is left names a parent that names it back, directly or at a distance,
    // so no ancestor of it reaches the root. Still something a person wrote:
    // shown from the oldest of them rather than dropped.
    for (entry in ordered) {
        if (entry.id in reachable || !placed.add(entry.id)) continue
        out += ThreadNode(entry, 0, descendantsOf(entry.id, mutableSetOf(entry.id)))
        if (entry.id !in collapsed) walk(entry.id, 1)
    }

    return out
}
