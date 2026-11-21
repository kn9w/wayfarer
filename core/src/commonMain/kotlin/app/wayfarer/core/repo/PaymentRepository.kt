package app.wayfarer.core.repo

import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PaymentTarget
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.UnsignedEvent
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
 * NIP-A3 payment targets (kind 10133): reading somebody's, and publishing yours.
 *
 * Read through the outbox model like everything else — from the author's own
 * write relays where their kind 10002 says where those are, and from the
 * approved read relays as a fallback. The shape is [ProfileRepository]'s,
 * because the question is the same one: a replaceable event, one per person,
 * fetched when their profile is opened.
 *
 * Fetched with the profile rather than with the feed. A payment address is
 * something you look up about a person you are already looking at; asking every
 * relay for the kind 10133 of every author in a timeline would be a query per
 * screenful for something almost nobody reads.
 */
class PaymentRepository(
    private val transport: RelayTransport,
    private val codec: NostrCodec,
    private val router: OutboxRouter,
    private val relayLists: RelayListRepository,
    private val clock: Clock,
) {
    private val state = MutableStateFlow<Map<PubKey, List<PaymentTarget>>>(emptyMap())

    /** When each cached list was published, so a stale event cannot overwrite a newer one. */
    private val publishedAt = mutableMapOf<PubKey, Long>()

    val targets: StateFlow<Map<PubKey, List<PaymentTarget>>> = state.asStateFlow()

    operator fun get(pubKey: PubKey): List<PaymentTarget> = state.value[pubKey].orEmpty()

    /**
     * Loads [pubKey]'s payment targets, preferring their advertised write relays.
     *
     * Returns whatever is cached when no approved relay can be routed to, which
     * is the ordinary answer for somebody whose relays are all still pending.
     */
    suspend fun load(pubKey: PubKey): List<PaymentTarget> {
        relayLists.ensureFor(setOf(pubKey))

        val outboxPlan = router.readPlanFor(setOf(pubKey), kinds = listOf(EventKind.PAYMENT_TARGETS), limitPerRelay = 1)
        val plan =
            if (outboxPlan.isEmpty) {
                router.discoveryPlanFor(setOf(pubKey), listOf(EventKind.PAYMENT_TARGETS))
            } else {
                outboxPlan.plan
            }
        if (plan.isEmpty()) return this[pubKey]

        for (received in transport.fetch(plan)) {
            absorb(received.event)
        }
        return this[pubKey]
    }

    /**
     * Files a kind 10133 into the cache, newest-wins. Ignores other kinds.
     *
     * Verified first, like every other event that steers what the app shows. An
     * unverified one would let any relay put its own bitcoin address on
     * somebody else's profile, which is the single worst thing a nostr client
     * can be talked into.
     */
    fun absorb(event: NostrEvent): List<PaymentTarget>? {
        if (event.kind != EventKind.PAYMENT_TARGETS) return null
        if (!codec.verify(event)) return null

        val known = publishedAt[event.pubKey]
        if (known != null && known >= event.createdAt) return state.value[event.pubKey]

        val parsed = PaymentTarget.fromEvent(event)
        publishedAt[event.pubKey] = event.createdAt
        state.value = state.value.plusBounded(event.pubKey, parsed, StoreLimits.PAYMENT_TARGETS)
        return parsed
    }

    /**
     * Publishes the signed-in account's own kind 10133.
     *
     * A replaceable event, so this is the whole list every time: publishing an
     * empty one is how somebody withdraws every address they had advertised,
     * and it has to reach the relays rather than being a no-op.
     */
    suspend fun publish(
        signer: EventSigner,
        author: PubKey,
        targets: List<PaymentTarget>,
    ): PublishResult {
        if (!signer.canSign) return PublishResult.Failure(PublishError.WatchOnlyAccount)

        val event =
            signer.sign(
                UnsignedEvent(
                    kind = EventKind.PAYMENT_TARGETS,
                    content = "",
                    tags = PaymentTarget.toTags(targets),
                    createdAt = clock.nowSeconds(),
                ),
            )

        val plan = router.publishPlanFor(author)
        if (plan.isEmpty) return PublishResult.Failure(PublishError.NoApprovedWriteRelay)

        val outcomes = transport.publish(event, plan.relays)
        absorb(event)

        val report = PublishReport(event, plan, outcomes)
        return if (report.anyAccepted) PublishResult.Success(report) else PublishResult.Failure(PublishError.Rejected(report))
    }
}
