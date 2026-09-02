package app.wayfarer.core.repo

import app.wayfarer.core.model.Article
import app.wayfarer.core.model.ArticleDraft
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * NIP-23 long-form articles (kind 30023).
 *
 * The one thing that differs from [FeedRepository]: kind 30023 is *addressable*,
 * so the store is keyed by `(author, dTag)` rather than by event id, and a later
 * event at the same address replaces an earlier one. Keying by event id instead
 * would show every revision of an article as a separate post.
 */
class ArticleRepository(
    private val transport: RelayTransport,
    private val codec: NostrCodec,
    private val router: OutboxRouter,
    private val relayLists: RelayListRepository,
    private val clock: Clock,
    private val events: EventStore? = null,
) {
    private val articles = MutableStateFlow<Map<String, Article>>(emptyMap())

    /** Keyed by NIP-01 address, `30023:<pubkey>:<dTag>`. */
    val all: StateFlow<Map<String, Article>> = articles.asStateFlow()

    /** Newest first. */
    fun by(authors: Set<PubKey>): Flow<List<Article>> =
        articles.map { current ->
            current.values.filter { it.author in authors }.sortedByDescending { it.publishedAt }
        }

    operator fun get(address: String): Article? = articles.value[address]

    /**
     * Loads [authors]' articles over the same outbox-routed plan the feed uses.
     *
     * In practice callers fetch kinds 1 and 30023 together on one REQ (see
     * [FeedRepository.load]); this exists for the article-only case.
     */
    suspend fun load(
        authors: Set<PubKey>,
        limitPerRelay: Int = 20,
    ): List<Article> {
        if (authors.isEmpty()) return emptyList()
        relayLists.ensureFor(authors)

        val plan =
            router.readPlanFor(
                authors = authors,
                kinds = listOf(EventKind.LONG_FORM),
                limitPerRelay = limitPerRelay,
                connected = transport.connected.value,
            )
        if (plan.isEmpty) return emptyList()

        for (received in transport.fetch(plan.plan)) {
            absorb(received.event, received.relay)
        }
        return articles.value.values.filter { it.author in authors }.sortedByDescending { it.publishedAt }
    }

    /**
     * Files a kind 30023 into the store, newest-wins per address. Signature is
     * verified first, as everywhere else.
     */
    fun absorb(
        event: NostrEvent,
        relay: RelayUrl?,
    ): Article? {
        if (event.kind != EventKind.LONG_FORM) return null
        if (!codec.verify(event)) return null

        val incoming = codec.readArticle(event) ?: return null
        events?.put(event)
        val withRelay = if (relay != null) incoming.mergeSeenOn(setOf(relay)) else incoming

        val existing = articles.value[withRelay.address]
        if (existing != null && existing.createdAt > withRelay.createdAt) return existing
        // Same address and same createdAt: keep the one we have, but remember the
        // extra relay it also arrived from.
        if (existing != null && existing.createdAt == withRelay.createdAt) {
            val merged = existing.mergeSeenOn(withRelay.seenOn)
            if (merged === existing) return existing
            articles.value = articles.value + (merged.address to merged)
            return merged
        }

        articles.value = articles.value + (withRelay.address to withRelay)
        return withRelay
    }

    /**
     * Publishes an article, creating or replacing.
     *
     * An empty [ArticleDraft.dTag] means a new article and gets a slug derived
     * from the title; a non-empty one is carried through untouched, which is
     * what makes an edit replace the original.
     */
    suspend fun publish(
        signer: EventSigner,
        author: PubKey,
        draft: ArticleDraft,
    ): PublishResult {
        if (!signer.canSign) return PublishResult.Failure(PublishError.WatchOnlyAccount)

        val dTag = draft.dTag.ifBlank { slug(draft.title, clock.nowSeconds()) }
        val event = signer.sign(codec.writeArticle(draft.copy(dTag = dTag), clock.nowSeconds()))

        val plan = router.publishPlanFor(author)
        if (plan.isEmpty) return PublishResult.Failure(PublishError.NoApprovedWriteRelay)

        val outcomes = transport.publish(event, plan.relays)
        absorb(event, null)

        val report = PublishReport(event, plan, outcomes)
        return if (report.anyAccepted) PublishResult.Success(report) else PublishResult.Failure(PublishError.Rejected(report))
    }

    companion object {
        /**
         * A URL-ish identifier from the title, with the timestamp appended so two
         * articles with the same title do not collide at the same address.
         */
        fun slug(
            title: String,
            now: Long,
        ): String {
            val base =
                title
                    .lowercase()
                    .map { if (it.isLetterOrDigit()) it else '-' }
                    .joinToString("")
                    .split('-')
                    .filter { it.isNotEmpty() }
                    .joinToString("-")
                    .take(60)
            return if (base.isEmpty()) "article-$now" else "$base-$now"
        }
    }
}
