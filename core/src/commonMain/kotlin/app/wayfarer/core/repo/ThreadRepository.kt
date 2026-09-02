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
) {
    private val comments = MutableStateFlow<Map<EventId, Comment>>(emptyMap())
    private val replies = MutableStateFlow<Map<EventId, Note>>(emptyMap())

    val allComments: StateFlow<Map<EventId, Comment>> = comments.asStateFlow()

    val allReplies: StateFlow<Map<EventId, Note>> = replies.asStateFlow()

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
                .map { ThreadEntry(it.id, it.author, it.createdAt, it.content, it.seenOn, isComment = true) }
        val fromReplies =
            replies.value.values
                // threadRoot as well as replyTo: a reply to a reply names its
                // parent in replyTo, so matching on that alone fetched nested
                // replies and then dropped them before the screen.
                .filter { root is ThreadRef.Event && (it.threadRoot == root.id || it.replyTo == root.id) }
                .map { ThreadEntry(it.id, it.author, it.createdAt, it.content, it.seenOn, isComment = false) }
        return (fromComments + fromReplies).sortedBy { it.createdAt }
    }

    /**
     * Fetches the thread under [root].
     *
     * Two filters, because the two conventions live in different tags: kind 1111
     * comments carry the root in an uppercase `E`/`A`, and kind 1 replies carry
     * it in a lowercase `e`. Asking for only one would silently hide half of
     * every conversation.
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
            if (absorb(received.event, received.relay)) absorbed++
        }
        return absorbed
    }

    /**
     * One filter per convention, never one filter for both.
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
            val existing = comments.value[incoming.id]
            val merged = existing?.mergeSeenOn(incoming.seenOn) ?: incoming
            if (merged !== existing) comments.value = comments.value + (merged.id to merged)
            return true
        }

        // A kind 1 with no reply target is somebody's own post that happens to
        // mention this event, not a reply to it.
        val reply = Note.fromEvent(event, relay)?.takeIf { it.replyTo != null } ?: return false
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
 * Flattened on purpose: a NIP-22 comment and a NIP-10 reply differ in how they
 * point at what they answer, and not at all in what a reader sees. [isComment]
 * is kept only so the UI can say which convention a reply arrived by.
 */
data class ThreadEntry(
    val id: EventId,
    val author: PubKey,
    val createdAt: Long,
    val content: String,
    val seenOn: Set<RelayUrl>,
    val isComment: Boolean,
)
