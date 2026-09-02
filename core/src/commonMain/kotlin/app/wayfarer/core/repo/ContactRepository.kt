package app.wayfarer.core.repo

import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signed-in account's public follow list (NIP-02, kind 3).
 *
 * A home feed is "notes by the people I follow", and the outbox router needs to
 * know whose relay lists to fetch. Following someone here is a public act: the
 * list is one signed event naming everybody, so adding one person republishes
 * the whole thing. [LocalFollowStore] is the private alternative.
 */
class ContactRepository(
    private val transport: RelayTransport,
    private val codec: NostrCodec,
    private val router: OutboxRouter,
    private val relayLists: RelayListRepository,
    private val clock: Clock,
) {
    private val state = MutableStateFlow<Set<PubKey>>(emptySet())

    private var latestCreatedAt = 0L

    /**
     * The newest kind 3 seen, kept whole.
     *
     * The parsed set is not enough to publish from: a kind 3 carries petnames on
     * its p-tags and a relay map in its content, and rebuilding the event from
     * pubkeys alone would erase both everywhere this account is used.
     */
    private var latestEvent: NostrEvent? = null

    val follows: StateFlow<Set<PubKey>> = state.asStateFlow()

    /** Whether a real list has been seen, as opposed to nothing having answered. */
    val loaded: Boolean get() = latestEvent != null

    suspend fun load(author: PubKey): Set<PubKey> {
        relayLists.ensureFor(setOf(author))

        val outboxPlan = router.readPlanFor(setOf(author), kinds = listOf(EventKind.CONTACT_LIST), limitPerRelay = 1)
        val plan =
            if (outboxPlan.isEmpty) {
                router.discoveryPlanFor(setOf(author), listOf(EventKind.CONTACT_LIST))
            } else {
                outboxPlan.plan
            }
        if (plan.isEmpty()) return state.value

        for (received in transport.fetch(plan)) {
            absorb(received.event, author)
        }
        return state.value
    }

    fun absorb(
        event: NostrEvent,
        author: PubKey,
    ) {
        if (event.kind != EventKind.CONTACT_LIST || event.pubKey != author) return
        if (event.createdAt <= latestCreatedAt) return
        latestCreatedAt = event.createdAt
        latestEvent = event
        state.value = codec.readFollows(event)
    }

    fun clear() {
        latestCreatedAt = 0
        latestEvent = null
        state.value = emptySet()
    }

    /** Adds [pubKey] to the public list and republishes it. */
    suspend fun follow(
        signer: EventSigner,
        author: PubKey,
        pubKey: PubKey,
    ): PublishResult = publish(signer, author, state.value + pubKey)

    /** Removes [pubKey] from the public list and republishes it. */
    suspend fun unfollow(
        signer: EventSigner,
        author: PubKey,
        pubKey: PubKey,
    ): PublishResult = publish(signer, author, state.value - pubKey)

    /**
     * Republishes the whole list, which is the only way NIP-02 has to change it.
     *
     * Built over [latestEvent] rather than from [follows]: every tag this app
     * does not model is carried across unchanged, and so is the content, so a
     * petname or a legacy relay map set by another client survives the edit.
     * Only the p-tags are rewritten, and a kept follow keeps its existing row
     * whole — including its petname — rather than being replaced by a bare one.
     */
    private suspend fun publish(
        signer: EventSigner,
        author: PubKey,
        follows: Set<PubKey>,
    ): PublishResult {
        if (!signer.canSign) return PublishResult.Failure(PublishError.WatchOnlyAccount)

        val previous = latestEvent
        val existing = previous?.tagRows("p").orEmpty()
        val kept = existing.filter { PubKey.parseOrNull(it.getOrNull(1)) in follows }
        val already = kept.mapNotNull { PubKey.parseOrNull(it.getOrNull(1)) }.toSet()
        val others = previous?.tags.orEmpty().filterNot { it.firstOrNull() == "p" }

        val event =
            signer.sign(
                UnsignedEvent(
                    kind = EventKind.CONTACT_LIST,
                    content = previous?.content.orEmpty(),
                    tags = others + kept + (follows - already).map { listOf("p", it.hex) },
                    createdAt = clock.nowSeconds(),
                ),
            )

        val plan = router.publishPlanFor(author)
        if (plan.isEmpty) return PublishResult.Failure(PublishError.NoApprovedWriteRelay)

        val outcomes = transport.publish(event, plan.relays)
        absorb(event, author)

        val report = PublishReport(event, plan, outcomes)
        return if (report.anyAccepted) PublishResult.Success(report) else PublishResult.Failure(PublishError.Rejected(report))
    }
}
