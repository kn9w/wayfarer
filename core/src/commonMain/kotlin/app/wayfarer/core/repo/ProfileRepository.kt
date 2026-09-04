package app.wayfarer.core.repo

import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.ProfileDraft
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.util.Clock
import app.wayfarer.core.util.StoreLimits
import app.wayfarer.core.util.plusBounded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Profiles (kind 0), read through the outbox model.
 *
 * A profile is fetched from the *author's own write relays* whenever their relay
 * list is known, and only from the general read relays as a fallback. That is
 * the difference between showing the profile its owner published and showing
 * whatever stale copy a popular relay happens to hold.
 */
class ProfileRepository(
    private val transport: RelayTransport,
    private val codec: NostrCodec,
    private val router: OutboxRouter,
    private val relayLists: RelayListRepository,
    private val clock: Clock,
) {
    private val state = MutableStateFlow<Map<PubKey, Profile>>(emptyMap())

    /** Raw kind 0 events, kept so an edit can merge over fields we do not model. */
    private val latestEvents = MutableStateFlow<Map<PubKey, NostrEvent>>(emptyMap())

    val profiles: StateFlow<Map<PubKey, Profile>> = state.asStateFlow()

    operator fun get(pubKey: PubKey): Profile? = state.value[pubKey]

    /**
     * Loads [pubKey]'s profile, preferring their advertised write relays.
     * Returns the cached value immediately if there is one; the fetch still runs
     * and updates [profiles].
     */
    suspend fun load(pubKey: PubKey): Profile? {
        relayLists.ensureFor(setOf(pubKey))

        val outboxPlan = router.readPlanFor(setOf(pubKey), kinds = listOf(EventKind.METADATA), limitPerRelay = 1)
        val plan =
            if (outboxPlan.isEmpty) {
                router.discoveryPlanFor(setOf(pubKey), listOf(EventKind.METADATA))
            } else {
                outboxPlan.plan
            }
        if (plan.isEmpty()) return state.value[pubKey]

        for (received in transport.fetch(plan)) {
            absorb(received.event)
        }
        return state.value[pubKey]
    }

    /**
     * Loads every profile in [pubKeys] that is not cached, in one round trip per
     * relay rather than one per person.
     *
     * The feed needs a name for each author it shows, and asking for them one at
     * a time is a fetch per author — fine for a handful of follows, absurd for a
     * screenful of strangers on a relay somebody is browsing.
     */
    suspend fun loadAll(pubKeys: Set<PubKey>) {
        val missing = pubKeys.filterNotTo(mutableSetOf()) { it in state.value }
        if (missing.isEmpty()) return

        relayLists.ensureFor(missing)

        val outboxPlan = router.readPlanFor(missing, kinds = listOf(EventKind.METADATA), limitPerRelay = missing.size)
        val plan =
            if (outboxPlan.isEmpty) {
                router.discoveryPlanFor(missing, listOf(EventKind.METADATA))
            } else {
                outboxPlan.plan
            }
        if (plan.isEmpty()) return

        for (received in transport.fetch(plan)) {
            absorb(received.event)
        }
    }

    /**
     * Files a kind 0 into the cache, newest-wins. Ignores other kinds.
     *
     * Verified first: a profile is the name, picture and lightning address the
     * whole UI shows for a person, so an unverified one lets any relay the user
     * reads through impersonate anybody to them.
     */
    fun absorb(event: NostrEvent) {
        if (event.kind != EventKind.METADATA) return
        if (!codec.verify(event)) return
        val parsed = codec.readProfile(event) ?: return

        val existing = state.value[event.pubKey]
        if (existing != null && existing.updatedAt >= event.createdAt) return

        state.value = state.value.plusBounded(event.pubKey, parsed.copy(updatedAt = event.createdAt), StoreLimits.PROFILES)
        latestEvents.value = latestEvents.value.plusBounded(event.pubKey, event, StoreLimits.PROFILES)
    }

    /**
     * Publishes an updated kind 0 for the signed-in account.
     *
     * The new content is merged over the previous event's JSON, so fields set by
     * other clients that Wayfarer does not model are preserved rather than wiped.
     */
    suspend fun publish(
        signer: EventSigner,
        author: PubKey,
        draft: ProfileDraft,
    ): PublishResult {
        if (!signer.canSign) return PublishResult.Failure(PublishError.WatchOnlyAccount)

        val unsigned =
            codec.writeProfile(
                previous = latestEvents.value[author],
                draft = draft,
                createdAt = clock.nowSeconds(),
            )
        val event = signer.sign(unsigned)

        val plan = router.publishPlanFor(author)
        if (plan.isEmpty) return PublishResult.Failure(PublishError.NoApprovedWriteRelay)

        val outcomes = transport.publish(event, plan.relays)
        absorb(event)

        val report = PublishReport(event, plan, outcomes)
        return if (report.anyAccepted) PublishResult.Success(report) else PublishResult.Failure(PublishError.Rejected(report))
    }
}
