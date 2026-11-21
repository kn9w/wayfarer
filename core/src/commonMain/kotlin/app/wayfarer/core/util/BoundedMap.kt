package app.wayfarer.core.util

/**
 * Insertion into a map that is not allowed to grow forever.
 *
 * Every store in this app is an immutable map inside a `StateFlow`, filled from
 * the network and never emptied. That is the right shape for something the UI
 * collects, and the wrong shape for something a relay can push at indefinitely:
 * a live subscription against a busy relay grows the heap without bound, and
 * because each insert copies the whole map, it also gets slower the longer the
 * session runs. Nothing malformed is required to do it — a chatty relay is
 * enough.
 *
 * So each store names a ceiling and inserts through here. Two consequences worth
 * knowing:
 *
 * Eviction is by insertion order, oldest first. Kotlin's `Map.plus` returns a
 * `LinkedHashMap`, and re-putting a key that is already present keeps its
 * original position — so a note that keeps being re-delivered by several relays
 * does not keep renewing its lease, and the order stays "the order things were
 * first seen". That is the right rule for a feed, where the oldest thing seen is
 * the thing least likely to still be on screen.
 *
 * A store is not what the screen is holding. The feed, an open thread and an
 * open article all keep their own snapshot of what they are showing, so
 * evicting an entry here does not take anything off the screen; the worst case
 * is that something re-arrives from a relay and is stored again.
 *
 * The copy per insert is still a copy — bounding it does not remove it. What it
 * does is put a ceiling on the cost, which is what turns unbounded quadratic
 * growth into a constant the caps below can be reasoned about.
 */
fun <K, V> Map<K, V>.plusBounded(
    key: K,
    value: V,
    max: Int,
): Map<K, V> {
    require(max > 0) { "a bounded store needs room for at least one entry" }

    val next = this + (key to value)
    if (next.size <= max) return next

    // Only ever one over, in practice: this runs on every insert, so the map is
    // trimmed as it crosses the line rather than in bulk. The loop is general
    // anyway, because a cap could be lowered between builds.
    val evicting = next.size - max
    val doomed = next.keys.asSequence().take(evicting).toSet()
    return next.filterKeys { it !in doomed }
}

/**
 * How many entries each in-memory store keeps.
 *
 * Deliberately generous. These are not tuned for a memory budget; they are
 * chosen so that reaching one means a session has run long enough that the
 * oldest entries are genuinely of no further interest, while leaving far more
 * than any screen displays at once. A phone showing forty notes at a time can
 * hold two thousand without noticing.
 */
object StoreLimits {
    /** Raw events, kept so a note can be shown or rebroadcast as it arrived. */
    const val EVENTS = 2_000

    /** Short notes, the busiest store in the app. */
    const val NOTES = 1_500

    /** Long-form articles. Far rarer than notes, and much larger each. */
    const val ARTICLES = 300

    /** Profiles, and the kind 0 each was parsed from. */
    const val PROFILES = 1_000

    /** Thread roots, comments and replies, capped separately. */
    const val THREAD_ENTRIES = 1_000

    /** Payment targets (kind 10133). */
    const val PAYMENT_TARGETS = 1_000

    /** NIP-11 documents, which are only ever fetched one user press at a time. */
    const val RELAY_INFO = 200
}
