package app.wayfarer.core.repo

import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayListEntry
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayList
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.util.Clock

/**
 * NIP-65 relay lists: fetching other people's, and publishing your own.
 *
 * This is the module that feeds the approval queue. Every relay named in every
 * kind 10002 the app sees is offered to [RelayDirectory], which either already
 * has a decision for it or files it as pending with the author it came from. The
 * user therefore ends up approving exactly the relays their own social graph
 * points at, rather than a list the app invented.
 */
class RelayListRepository(
    private val transport: RelayTransport,
    private val codec: NostrCodec,
    private val cache: RelayListCache,
    private val router: OutboxRouter,
    private val directory: RelayDirectory,
    private val clock: Clock,
    /**
     * Names a pubkey for the reason strings shown on the relay screen.
     *
     * Injected rather than calling [PubKey.abbreviated], which is hex: these
     * strings are read by a person deciding whether to allow a relay, and a
     * truncated hex key tells them nothing they can recognise. Defaults to hex
     * only so tests and non-UI callers need not supply a bech32 codec.
     */
    private val describe: (PubKey) -> String = { it.abbreviated() },
) {
    /**
     * Fetches the kind 10002 of every author in [authors] that is not cached yet,
     * from the approved read relays. Returns the authors still without a list.
     */
    suspend fun ensureFor(authors: Set<PubKey>): Set<PubKey> {
        val missing = cache.missing(authors)
        if (missing.isEmpty()) return emptySet()

        val plan = router.discoveryPlanFor(missing, listOf(EventKind.RELAY_LIST))
        if (plan.isEmpty()) return missing

        for (received in transport.fetch(plan)) {
            absorb(received.event)
        }
        return cache.missing(missing)
    }

    /** Re-fetches one author's relay list even if it is already cached. */
    suspend fun refresh(author: PubKey): RelayList? {
        val plan = router.discoveryPlanFor(setOf(author), listOf(EventKind.RELAY_LIST))
        for (received in transport.fetch(plan)) {
            absorb(received.event)
        }
        return cache[author]
    }

    /**
     * Files a kind 10002 into the cache and offers every relay it names to the
     * approval queue. Safe to call with any event; non-10002 is ignored.
     */
    fun absorb(event: NostrEvent): RelayList? {
        if (event.kind != EventKind.RELAY_LIST) return null
        val entries = codec.readRelayList(event)
        val list = RelayList(event.pubKey, event.createdAt, entries)
        if (!cache.put(list)) return cache[event.pubKey]
        return list
    }

    /**
     * Records the relays named by [list] as pending, if the user has no decision
     * on them yet. Split out from [absorb] because it suspends and absorb is
     * called from the event-arrival path.
     */
    suspend fun offerToDirectory(
        list: RelayList,
        isOwnAccount: Boolean,
    ) {
        val source = if (isOwnAccount) DiscoverySource.OWN_RELAY_LIST else DiscoverySource.AUTHOR_RELAY_LIST
        val detail = if (isOwnAccount) "your relay list" else "relay list of ${describe(list.author)}"
        directory.note(list.entries.map { it.url }, DiscoveryReason(source, detail))
    }

    /**
     * Publishes the signed-in account's own kind 10002.
     *
     * Published to the account's *current* write relays as well as the new ones,
     * so a relay being dropped from the list still learns that it was dropped.
     */
    suspend fun publishOwn(
        signer: EventSigner,
        author: PubKey,
        entries: List<RelayListEntry>,
    ): PublishResult {
        if (!signer.canSign) return PublishResult.Failure(PublishError.WatchOnlyAccount)

        val event = signer.sign(codec.writeRelayList(entries, clock.nowSeconds()))

        val plan = router.publishPlanFor(author)
        val newTargets =
            directory.writable(
                entries.filter { it.write }.map { it.url },
                DiscoveryReason(DiscoverySource.OWN_RELAY_LIST, "your write relay"),
            )
        val relays = plan.relays + newTargets
        if (relays.isEmpty()) {
            return PublishResult.Failure(PublishError.NoApprovedWriteRelay)
        }

        val outcomes = transport.publish(event, relays)
        val report = PublishReport(event, plan.copy(relays = relays), outcomes)

        // Trust our own publish: update the cache immediately so routing uses the
        // new list without waiting for the event to come back from a relay.
        cache.put(RelayList(author, event.createdAt, entries))

        return if (report.anyAccepted) PublishResult.Success(report) else PublishResult.Failure(PublishError.Rejected(report))
    }
}
