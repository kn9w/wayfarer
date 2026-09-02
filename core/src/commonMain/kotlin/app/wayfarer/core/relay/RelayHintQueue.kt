package app.wayfarer.core.relay

import app.wayfarer.core.model.DiscoveryReason

/**
 * Relay hints noticed while reading, held until something can act on them.
 *
 * Events are absorbed inside the feed's subscription collector, where anything
 * that suspends stalls every event queued behind it — the mistake that made
 * streamed notes crawl before profile lookups were batched. Recording a hint in
 * the permission directory is a suspending, persisting write, so it cannot
 * happen there. Offering is a cheap append instead, and the caller drains on its
 * own schedule.
 *
 * Keyed by reason so a hint keeps the story of what caused it: the same relay
 * named by two different people is two reasons for wanting it, and the relay
 * screen shows both.
 */
class RelayHintQueue {
    private val pending = mutableMapOf<DiscoveryReason, MutableSet<String>>()

    fun offer(
        hints: Collection<String>,
        reason: DiscoveryReason,
    ) {
        if (hints.isEmpty()) return
        pending.getOrPut(reason) { mutableSetOf() } += hints
    }

    /** Takes everything offered so far and empties the queue. */
    fun drain(): Map<DiscoveryReason, Set<String>> {
        if (pending.isEmpty()) return emptyMap()
        val taken = pending.mapValues { (_, urls) -> urls.toSet() }
        pending.clear()
        return taken
    }

    val isEmpty: Boolean get() = pending.isEmpty()
}
