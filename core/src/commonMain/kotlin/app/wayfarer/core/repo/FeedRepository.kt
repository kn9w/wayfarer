package app.wayfarer.core.repo

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * Text notes (kind 1): reading them from where their authors publish, and
 * publishing your own to where their readers will find them.
 */
class FeedRepository(
    private val transport: RelayTransport,
    private val codec: NostrCodec,
    private val router: OutboxRouter,
    private val relayLists: RelayListRepository,
    private val clock: Clock,
    /**
     * Long-form events arrive on the same REQ as text notes — one round trip
     * instead of two — and are handed straight over to their own store.
     */
    private val articles: ArticleRepository? = null,
) {
    private val notes = MutableStateFlow<Map<EventId, Note>>(emptyMap())

    val allNotes: StateFlow<Map<EventId, Note>> = notes.asStateFlow()

    /** Newest first. */
    fun notesBy(authors: Set<PubKey>): Flow<List<Note>> =
        notes.map { current ->
            current.values.filter { it.author in authors }.sortedByDescending { it.createdAt }
        }

    data class LoadResult(
        val notes: List<Note>,
        /** Authors no approved relay serves — the outbox gap the UI should surface. */
        val unreachableAuthors: Set<PubKey>,
        /** Authors with no relay list, fetched from approved relays as a guess. */
        val guessedAuthors: Set<PubKey>,
        val relaysQueried: Set<RelayUrl>,
    )

    /**
     * One-shot load of [authors]' recent notes, routed per author to the relays
     * those authors actually publish to.
     */
    suspend fun load(
        authors: Set<PubKey>,
        limitPerRelay: Int = 50,
        since: Long? = null,
    ): LoadResult {
        if (authors.isEmpty()) return LoadResult(emptyList(), emptySet(), emptySet(), emptySet())

        relayLists.ensureFor(authors)

        val readPlan =
            router.readPlanFor(
                authors = authors,
                kinds = FEED_KINDS,
                limitPerRelay = limitPerRelay,
                since = since,
                connected = transport.connected.value,
            )
        if (readPlan.isEmpty) {
            return LoadResult(emptyList(), readPlan.unreachable, readPlan.guessed, emptySet())
        }

        for (received in transport.fetch(readPlan.plan)) {
            absorb(received.event, received.relay)
            articles?.absorb(received.event, received.relay)
        }

        val loaded = notes.value.values.filter { it.author in authors }.sortedByDescending { it.createdAt }
        return LoadResult(loaded, readPlan.unreachable, readPlan.guessed, readPlan.plan.keys)
    }

    /**
     * Reads whatever [relays] are currently handing out, with no author filter.
     *
     * This is what a brand-new user actually needs: they follow nobody, so an
     * outbox-routed feed is necessarily empty, and "here is what is on the relay
     * you chose" is the only thing that can be shown. It is deliberately a
     * separate method rather than a fallback inside [load] — the two are not the
     * same claim, and the UI has to be able to say which one it is showing.
     */
    suspend fun loadFromRelays(
        relays: Set<RelayUrl>,
        limitPerRelay: Int = 50,
    ): LoadResult {
        if (relays.isEmpty()) return LoadResult(emptyList(), emptySet(), emptySet(), emptySet())

        val plan = router.relayPlanFor(relays, FEED_KINDS, limitPerRelay)
        if (plan.isEmpty()) return LoadResult(emptyList(), emptySet(), emptySet(), emptySet())

        val seen = mutableSetOf<EventId>()
        for (received in transport.fetch(plan)) {
            absorb(received.event, received.relay)?.let { seen += it.id }
            articles?.absorb(received.event, received.relay)
        }

        val loaded = notes.value.values.filter { it.id in seen }.sortedByDescending { it.createdAt }
        return LoadResult(loaded, emptySet(), emptySet(), plan.keys)
    }

    /** A live subscription over the same outbox-routed plan. */
    suspend fun live(
        authors: Set<PubKey>,
        since: Long,
    ): Flow<Note> {
        val readPlan =
            router.readPlanFor(
                authors = authors,
                kinds = FEED_KINDS,
                since = since,
                connected = transport.connected.value,
            )
        return transport
            .subscribe(readPlan.plan)
            .mapNotNull { received ->
                articles?.absorb(received.event, received.relay)
                absorb(received.event, received.relay)
            }
    }

    /**
     * A live subscription over the relays themselves, with no author filter.
     *
     * The browsing counterpart to [live], standing in the same relation to it as
     * [loadFromRelays] does to [load]. It exists because a user who follows
     * nobody has no authors to route by, and without it that user — which is
     * every new user — would have no open subscription at all, so no relay
     * socket would stay up and nothing would arrive between manual refreshes.
     */
    suspend fun liveFromRelays(
        relays: Set<RelayUrl>,
        since: Long,
    ): Flow<Note> {
        // No limit: a limit caps the stored events a relay replays before EOSE,
        // and this subscription exists for what arrives after it.
        val plan = router.relayPlanFor(relays, FEED_KINDS, since = since)
        if (plan.isEmpty()) return emptyFlow()
        return transport
            .subscribe(plan)
            .mapNotNull { received ->
                articles?.absorb(received.event, received.relay)
                absorb(received.event, received.relay)
            }
    }

    /**
     * Adds an event to the note store, merging relay provenance if it is already
     * known. Signature is verified here — an event that fails is dropped, not
     * shown with a warning, because a client that renders unverified events is
     * a client whose feed a relay can forge.
     */
    fun absorb(
        event: NostrEvent,
        relay: RelayUrl?,
    ): Note? {
        if (event.kind != EventKind.TEXT_NOTE) return null
        if (!codec.verify(event)) return null

        val incoming = Note.fromEvent(event, relay) ?: return null
        val existing = notes.value[incoming.id]
        val merged = existing?.mergeSeenOn(incoming.seenOn) ?: incoming
        if (merged === existing) return existing
        notes.value = notes.value + (merged.id to merged)
        return merged
    }

    /**
     * Publishes a note.
     *
     * [mentions] drives the inbox half of outbox routing: each mentioned pubkey
     * becomes a `p` tag *and* pulls that user's read relays into the target set,
     * so the mention actually reaches them.
     */
    suspend fun post(
        signer: EventSigner,
        author: PubKey,
        content: String,
        mentions: Set<PubKey> = emptySet(),
        replyTo: Note? = null,
    ): PublishResult {
        if (!signer.canSign) return PublishResult.Failure(PublishError.WatchOnlyAccount)

        val allMentions = mentions + setOfNotNull(replyTo?.author)
        relayLists.ensureFor(allMentions)

        val tags = mutableListOf<List<String>>()
        replyTo?.let { tags += listOf("e", it.id.hex, "", "root") }
        for (mentioned in allMentions) {
            tags += listOf("p", mentioned.hex)
        }

        val event =
            signer.sign(
                UnsignedEvent(
                    kind = EventKind.TEXT_NOTE,
                    content = content,
                    tags = tags,
                    createdAt = clock.nowSeconds(),
                ),
            )

        val plan = router.publishPlanFor(author, allMentions)
        if (plan.isEmpty) return PublishResult.Failure(PublishError.NoApprovedWriteRelay)

        val outcomes = transport.publish(event, plan.relays)
        absorb(event, null)

        val report = PublishReport(event, plan, outcomes)
        return if (report.anyAccepted) PublishResult.Success(report) else PublishResult.Failure(PublishError.Rejected(report))
    }

    private companion object {
        /** Text notes and long-form articles share one subscription. */
        val FEED_KINDS = listOf(EventKind.TEXT_NOTE, EventKind.LONG_FORM)
    }
}
