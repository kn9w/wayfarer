package app.wayfarer.core.repo

import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.outbox.OutboxRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signed-in account's follow list (NIP-02, kind 3).
 *
 * Read-only here: this app does not edit follows. It needs the list because a
 * home feed is "notes by the people I follow", and because the outbox router
 * needs to know whose relay lists to fetch.
 */
class ContactRepository(
    private val transport: RelayTransport,
    private val codec: NostrCodec,
    private val router: OutboxRouter,
    private val relayLists: RelayListRepository,
) {
    private val state = MutableStateFlow<Set<PubKey>>(emptySet())

    private var latestCreatedAt = 0L

    val follows: StateFlow<Set<PubKey>> = state.asStateFlow()

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
        state.value = codec.readFollows(event)
    }

    fun clear() {
        latestCreatedAt = 0
        state.value = emptySet()
    }
}
