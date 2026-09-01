package app.wayfarer.core.outbox

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayListEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Inverts [lists] into "who publishes here", per relay.
 *
 * The relay permission screen ranks by this: a relay fifty of your follows
 * publish to is a more consequential decision than one a single author named
 * once, and with hundreds of relays queued that ordering is the difference
 * between a usable list and an unusable one.
 *
 * Only write ("outbox") entries count. A relay an author merely *reads* from is
 * not somewhere their posts can be fetched, so counting it would inflate the
 * number with relays that would return nothing.
 *
 * Pure, so it is testable without a network and cheap enough to recompute
 * whenever either input changes.
 */
fun publishersByRelay(lists: Map<PubKey, RelayList>): Map<RelayUrl, Set<PubKey>> =
    buildMap<RelayUrl, MutableSet<PubKey>> {
        for ((author, list) in lists) {
            // distinct(): an author listing the same relay twice is still one
            // author, and a Set of authors would hide that anyway.
            for (relay in list.outbox.distinct()) {
                getOrPut(relay) { mutableSetOf() } += author
            }
        }
    }

/** One author's NIP-65 advertisement, as last seen. */
data class RelayList(
    val author: PubKey,
    val createdAt: Long,
    val entries: List<RelayListEntry>,
) {
    /** Where this author publishes. Read their notes here. */
    val outbox: List<RelayUrl> get() = entries.filter { it.write }.map { it.url }

    /** Where this author reads. Send them mentions here. */
    val inbox: List<RelayUrl> get() = entries.filter { it.read }.map { it.url }

    companion object {
        val EMPTY_AUTHOR_LIST = emptyList<RelayUrl>()
    }
}

/**
 * In-memory store of every kind 10002 the app has seen, newest-wins.
 *
 * This is the lookup table the outbox router runs on. It is intentionally not
 * persisted: relay lists change, and a stale one silently routes a user's posts
 * to relays their followers stopped reading. Refetching on launch is cheap.
 */
class RelayListCache {
    private val state = MutableStateFlow<Map<PubKey, RelayList>>(emptyMap())

    val lists: StateFlow<Map<PubKey, RelayList>> = state.asStateFlow()

    operator fun get(author: PubKey): RelayList? = state.value[author]

    /** Stores [list] unless a newer one for the same author is already held. */
    fun put(list: RelayList): Boolean {
        var stored = false
        state.update { current ->
            val existing = current[list.author]
            if (existing != null && existing.createdAt >= list.createdAt) {
                current
            } else {
                stored = true
                current + (list.author to list)
            }
        }
        return stored
    }

    /** Authors with no cached relay list — the ones a discovery fetch must cover. */
    fun missing(authors: Collection<PubKey>): Set<PubKey> {
        val known = state.value
        return authors.filterNotTo(mutableSetOf()) { it in known }
    }

    private inline fun MutableStateFlow<Map<PubKey, RelayList>>.update(block: (Map<PubKey, RelayList>) -> Map<PubKey, RelayList>) {
        while (true) {
            val current = value
            val next = block(current)
            if (next === current || compareAndSet(current, next)) return
        }
    }
}
