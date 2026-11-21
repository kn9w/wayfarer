package app.wayfarer.core.outbox

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl

/**
 * Picks which relays to actually query, given each author's candidate relays.
 *
 * The naive outbox implementation opens a connection to every relay every follow
 * advertises — hundreds of sockets for a few hundred follows. This is the
 * standard fix: a greedy set cover that keeps asking "which single relay serves
 * the most authors I am still short on?" until every author is covered
 * [redundancy] times or the relay budget runs out.
 *
 * Pure and deterministic, so the routing decisions are unit-testable without a
 * network, a signer, or a clock.
 */
object RelayCoverage {
    data class Selection(
        /** Relay -> the authors that relay is responsible for in this plan. */
        val assignments: Map<RelayUrl, Set<PubKey>>,
        /** Authors no approved relay could serve. Surfaced in the UI, not swallowed. */
        val uncovered: Set<PubKey>,
    )

    /**
     * @param candidatesByAuthor each author's already-approved candidate relays.
     *   Authors mapping to an empty list are reported in [Selection.uncovered].
     * @param redundancy how many relays should serve each author. 2 is the usual
     *   compromise: one relay being down or lagging does not hide an author.
     * @param maxRelays hard ceiling on sockets for this plan.
     * @param preferred relays to favour on a tie — in practice the ones already
     *   connected, so a feed refresh reuses sockets instead of opening new ones.
     */
    fun select(
        candidatesByAuthor: Map<PubKey, List<RelayUrl>>,
        redundancy: Int,
        maxRelays: Int,
        preferred: Set<RelayUrl> = emptySet(),
    ): Selection {
        require(redundancy >= 1) { "redundancy must be at least 1" }

        val authorsByRelay = mutableMapOf<RelayUrl, MutableSet<PubKey>>()
        val uncovered = mutableSetOf<PubKey>()
        for ((author, relays) in candidatesByAuthor) {
            if (relays.isEmpty()) {
                uncovered += author
                continue
            }
            for (relay in relays) {
                authorsByRelay.getOrPut(relay) { mutableSetOf() } += author
            }
        }

        val shortfall = mutableMapOf<PubKey, Int>()
        for ((author, relays) in candidatesByAuthor) {
            if (relays.isEmpty()) continue
            // An author advertising fewer relays than `redundancy` can never reach
            // it; asking for more than they have would loop without progress.
            shortfall[author] = minOf(redundancy, relays.distinct().size)
        }

        val assignments = LinkedHashMap<RelayUrl, Set<PubKey>>()
        val remaining = authorsByRelay.keys.toMutableSet()

        while (assignments.size < maxRelays && shortfall.isNotEmpty()) {
            var best: RelayUrl? = null
            var bestGain = 0
            for (relay in remaining) {
                val gain = authorsByRelay.getValue(relay).count { it in shortfall }
                if (gain > bestGain || (gain == bestGain && gain > 0 && best != null && breaksTie(relay, best, preferred))) {
                    best = relay
                    bestGain = gain
                }
            }
            if (best == null || bestGain == 0) break

            val served = authorsByRelay.getValue(best).filterTo(mutableSetOf()) { it in shortfall }
            assignments[best] = served
            remaining -= best
            for (author in served) {
                val left = shortfall.getValue(author) - 1
                if (left <= 0) shortfall.remove(author) else shortfall[author] = left
            }
        }

        // Anything still short after the budget ran out is covered by fewer relays
        // than asked for, which is fine; only a total miss counts as uncovered.
        val covered = assignments.values.flatMapTo(mutableSetOf()) { it }
        for (author in shortfall.keys) {
            if (author !in covered) uncovered += author
        }

        return Selection(assignments, uncovered)
    }

    /** Deterministic tie-break: a preferred (already-connected) relay wins, then url order. */
    private fun breaksTie(
        candidate: RelayUrl,
        incumbent: RelayUrl,
        preferred: Set<RelayUrl>,
    ): Boolean {
        val candidatePreferred = candidate in preferred
        val incumbentPreferred = incumbent in preferred
        if (candidatePreferred != incumbentPreferred) return candidatePreferred
        return candidate.url < incumbent.url
    }
}
