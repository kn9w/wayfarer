package app.wayfarer.core.outbox

import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.ReqFilter
import app.wayfarer.core.relay.RelayDirectory

/**
 * Tunables for outbox routing. The defaults are conservative on purpose: this
 * app opens few sockets and asks each relay only for what it is the right source
 * for.
 */
data class OutboxConfig(
    /** Relays per author when reading their notes. */
    val readRedundancy: Int = 2,
    /** Ceiling on relays in a single read plan. */
    val maxReadRelays: Int = 12,
    /** Own write relays used when publishing. */
    val maxOwnWriteRelays: Int = 6,
    /** Inbox relays used per mentioned user when publishing. */
    val inboxRelaysPerMention: Int = 2,
    /** Ceiling on relays a single publish fans out to. */
    val maxPublishRelays: Int = 20,
    /**
     * What to do about an author who has published no kind 10002 at all.
     *
     * Strictly, the outbox model has no answer: without a relay list there is no
     * claim about where that author publishes. Answering "nowhere" would hide a
     * large share of nostr, since plenty of accounts have never published one, so
     * the default is to fall back to the relays the user approved for reading and
     * say so in the UI. Set false for strict behaviour: no relay list, no fetch.
     */
    val fallbackToApprovedReadRelays: Boolean = true,
)

/**
 * Turns "which authors do I want?" into "which relay do I ask for which authors?".
 *
 * Every relay set produced here has already been through [RelayDirectory], so a
 * plan can only ever name approved relays; relays that were wanted but are not
 * approved are recorded as pending with the reason they were wanted, which is
 * what fills the approval queue in settings.
 */
class OutboxRouter(
    private val relayLists: RelayListCache,
    private val directory: RelayDirectory,
    private val config: OutboxConfig = OutboxConfig(),
) {
    data class ReadPlan(
        val plan: Map<RelayUrl, List<ReqFilter>>,
        /** Authors that no approved relay serves — usually pending approvals. */
        val unreachable: Set<PubKey>,
        /**
         * Authors with no kind 10002, fetched from the approved read relays as a
         * guess. Surfaced so the UI can be honest that these are not outbox-routed.
         */
        val guessed: Set<PubKey> = emptySet(),
    ) {
        val isEmpty: Boolean get() = plan.isEmpty()
    }

    data class PublishPlan(
        val relays: Set<RelayUrl>,
        /** Own write relays. Empty means the note cannot be published at all. */
        val ownWrite: Set<RelayUrl>,
        /** Inbox relays added so the mentioned users actually receive it. */
        val mentionInbox: Set<RelayUrl>,
    ) {
        val isEmpty: Boolean get() = relays.isEmpty()
    }

    /**
     * Where to read [authors]' own events: each author's advertised *write*
     * relays. This is the outbox model's core claim — an author's content lives
     * where the author put it, not on whatever relay we happen to like.
     */
    suspend fun readPlanFor(
        authors: Set<PubKey>,
        kinds: List<Int>,
        limitPerRelay: Int? = null,
        since: Long? = null,
        connected: Set<RelayUrl> = emptySet(),
    ): ReadPlan {
        if (authors.isEmpty()) return ReadPlan(emptyMap(), emptySet())

        val fallback = if (config.fallbackToApprovedReadRelays) approvedReadRelays().toList() else emptyList()

        val candidates = mutableMapOf<PubKey, List<RelayUrl>>()
        val guessed = mutableSetOf<PubKey>()
        for (author in authors) {
            val advertised = relayLists[author]?.outbox.orEmpty()
            candidates[author] =
                if (advertised.isEmpty()) {
                    // No relay list to route by. Either guess, or report the author
                    // as unreachable — never quietly broadcast to every relay.
                    guessed += author
                    fallback
                } else {
                    directory
                        .readable(
                            advertised,
                            DiscoveryReason(DiscoverySource.AUTHOR_RELAY_LIST, "write relay of ${author.abbreviated()}"),
                        ).toList()
                }
        }

        val selection =
            RelayCoverage.select(
                candidatesByAuthor = candidates,
                redundancy = config.readRedundancy,
                maxRelays = config.maxReadRelays,
                preferred = connected,
            )

        val plan =
            selection.assignments.mapValues { (_, assigned) ->
                listOf(
                    ReqFilter(
                        authors = assigned.map { it.hex }.sorted(),
                        kinds = kinds,
                        since = since,
                        limit = limitPerRelay,
                    ),
                )
            }

        return ReadPlan(plan, selection.uncovered, guessed - selection.uncovered)
    }

    /**
     * Where a note by [author] mentioning [mentions] must go.
     *
     * NIP-65: publish to your own write relays so your followers (who read your
     * outbox) find it, *and* to each mentioned user's read relays so the mention
     * actually lands in their inbox. Skipping the second half is the single most
     * common way clients silently break mentions.
     */
    suspend fun publishPlanFor(
        author: PubKey,
        mentions: Set<PubKey> = emptySet(),
    ): PublishPlan {
        val ownWrite =
            directory
                .writable(
                    relayLists[author]?.outbox.orEmpty(),
                    DiscoveryReason(DiscoverySource.OWN_RELAY_LIST, "your write relay"),
                ).take(config.maxOwnWriteRelays)
                .toSet()
                .ifEmpty {
                    // No usable kind 10002 yet (fresh account, or none approved).
                    // Fall back to everything the user approved for writing, so a
                    // first post is possible before the relay list exists.
                    directory.grants.values.filter { it.write }.map { it.url }.take(config.maxOwnWriteRelays).toSet()
                }

        val inbox = mutableSetOf<RelayUrl>()
        for (mentioned in mentions) {
            if (mentioned == author) continue
            val theirInbox = relayLists[mentioned]?.inbox.orEmpty()
            if (theirInbox.isEmpty()) continue
            inbox +=
                directory
                    .writable(
                        theirInbox,
                        DiscoveryReason(DiscoverySource.AUTHOR_RELAY_LIST, "inbox relay of ${mentioned.abbreviated()}"),
                    ).take(config.inboxRelaysPerMention)
        }

        val all = (ownWrite + inbox).take(config.maxPublishRelays).toSet()
        return PublishPlan(relays = all, ownWrite = ownWrite, mentionInbox = inbox - ownWrite)
    }

    /**
     * Where mentions of [me] arrive: my own advertised read relays. Falls back to
     * everything approved for reading before a relay list exists.
     */
    suspend fun inboxPlanFor(
        me: PubKey,
        kinds: List<Int>,
        limitPerRelay: Int? = null,
    ): Map<RelayUrl, List<ReqFilter>> {
        val relays =
            directory
                .readable(
                    relayLists[me]?.inbox.orEmpty(),
                    DiscoveryReason(DiscoverySource.OWN_RELAY_LIST, "your read relay"),
                ).ifEmpty { approvedReadRelays() }

        val filter = ReqFilter(kinds = kinds, tags = mapOf("p" to listOf(me.hex)), limit = limitPerRelay)
        return relays.associateWith { listOf(filter) }
    }

    /**
     * Where to look for things we cannot route yet — an author's kind 10002 and
     * kind 0 when we have never seen either. Uses every approved read relay,
     * because by definition there is no better-informed choice available.
     */
    fun discoveryRelays(): Set<RelayUrl> = approvedReadRelays()

    private fun approvedReadRelays(): Set<RelayUrl> = directory.grants.values.filter { it.read }.mapTo(mutableSetOf()) { it.url }

    /** A discovery plan for the relay lists and profiles of [authors]. */
    fun discoveryPlanFor(
        authors: Set<PubKey>,
        kinds: List<Int>,
    ): Map<RelayUrl, List<ReqFilter>> {
        if (authors.isEmpty()) return emptyMap()
        val filter = ReqFilter(authors = authors.map { it.hex }.sorted(), kinds = kinds)
        return discoveryRelays().associateWith { listOf(filter) }
    }
}
