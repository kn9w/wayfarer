package app.wayfarer.core.relay

import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.PendingRelay
import app.wayfarer.core.model.RelayDirectorySnapshot
import app.wayfarer.core.model.RelayGrant
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single authority on which relays this app may talk to.
 *
 * Deny by default: [readable] and [writable] return only relays the user has
 * granted, and every rejected relay is recorded in [pending] together with the
 * reason it was wanted. Nothing in the app is allowed to build a relay set for
 * the transport except by passing it through here first.
 *
 * The gate is enforced a second time at the socket layer — see
 * `GatedWebsocketBuilder` in the `nostr-quartz` module, which refuses to open a
 * connection to any URL this directory does not approve. That is deliberate
 * belt-and-braces: a routing bug upstream still cannot leak a connection.
 */
class RelayDirectory(
    private val clock: Clock,
    initial: RelayDirectorySnapshot = RelayDirectorySnapshot(),
    private val persistence: RelayDirectoryStore? = null,
) : RelayAccessPolicy {
    private val writeLock = Mutex()

    private val state = MutableStateFlow(initial)

    /** Grants only, sorted by display name, for the settings screen. */
    val snapshot: StateFlow<RelayDirectorySnapshot> = state.asStateFlow()

    val grants: Map<RelayUrl, RelayGrant> get() = state.value.grants

    val pending: Map<RelayUrl, PendingRelay> get() = state.value.pending

    override fun isApproved(url: RelayUrl): Boolean = state.value.grants[url]?.isApproved == true

    fun canRead(url: RelayUrl): Boolean = state.value.grants[url]?.read == true

    fun canWrite(url: RelayUrl): Boolean = state.value.grants[url]?.write == true

    /**
     * Narrows [candidates] to the relays approved for reading, recording every
     * rejected one as pending with [reason].
     *
     * The recording is the point: the pending list in settings is populated as a
     * side effect of the app doing its normal outbox routing, so the user sees
     * exactly the relays their follows are actually asking them to reach.
     */
    suspend fun readable(
        candidates: Collection<RelayUrl>,
        reason: DiscoveryReason,
    ): Set<RelayUrl> = partition(candidates, reason) { it.read }

    /** Narrows [candidates] to the relays approved for publishing. See [readable]. */
    suspend fun writable(
        candidates: Collection<RelayUrl>,
        reason: DiscoveryReason,
    ): Set<RelayUrl> = partition(candidates, reason) { it.write }

    private suspend fun partition(
        candidates: Collection<RelayUrl>,
        reason: DiscoveryReason,
        permitted: (RelayGrant) -> Boolean,
    ): Set<RelayUrl> {
        if (candidates.isEmpty()) return emptySet()

        val current = state.value
        val allowed = LinkedHashSet<RelayUrl>(candidates.size)
        var unknown: MutableSet<RelayUrl>? = null

        for (url in candidates) {
            val grant = current.grants[url]
            when {
                grant != null && permitted(grant) -> allowed += url
                // Known but not permitted for this direction, or explicitly denied:
                // an existing decision, so nothing to ask the user about again.
                grant != null || url in current.denied -> Unit
                else -> (unknown ?: mutableSetOf<RelayUrl>().also { unknown = it }) += url
            }
        }

        unknown?.let { note(it, reason) }
        return allowed
    }

    /** Records relays as pending without asking to use them right now. */
    suspend fun note(
        urls: Collection<RelayUrl>,
        reason: DiscoveryReason,
    ) {
        if (urls.isEmpty()) return
        mutate { current ->
            val now = clock.nowSeconds()
            val pending = current.pending.toMutableMap()
            var changed = false
            for (url in urls) {
                if (url in current.grants || url in current.denied) continue
                val existing = pending[url]
                val updated =
                    existing?.merge(setOf(reason), now)
                        ?: PendingRelay(url, setOf(reason), firstSeenAt = now, lastSeenAt = now)
                if (updated != existing) {
                    pending[url] = updated
                    changed = true
                }
            }
            if (changed) current.copy(pending = pending) else current
        }
    }

    /**
     * Grants [url] the given permissions and clears it from the pending queue.
     * Setting both flags false is a revocation, not a denial: the relay returns
     * to pending only if something asks for it again.
     */
    suspend fun approve(
        url: RelayUrl,
        read: Boolean,
        write: Boolean,
    ) = mutate { current ->
        val grants = current.grants.toMutableMap()
        if (read || write) {
            grants[url] = RelayGrant(url, read, write)
        } else {
            grants.remove(url)
        }
        current.copy(
            grants = grants,
            pending = current.pending - url,
            denied = current.denied - url,
        )
    }

    /** Revokes any grant and remembers the rejection so it stops re-appearing. */
    suspend fun deny(url: RelayUrl) =
        mutate { current ->
            current.copy(
                grants = current.grants - url,
                pending = current.pending - url,
                denied = current.denied + url,
            )
        }

    /**
     * Stars or unstars a relay.
     *
     * Not a permission: starring changes nothing about what may be fetched, so
     * unlike the mutators around it this one has no bearing on routing and must
     * not be treated as a reason to reload anything.
     */
    suspend fun setFavourite(
        url: RelayUrl,
        favourite: Boolean,
    ) = mutate { current ->
        current.copy(favourites = if (favourite) current.favourites + url else current.favourites - url)
    }

    /** Forgets a relay entirely — no grant, no pending entry, no denial. */
    suspend fun forget(url: RelayUrl) =
        mutate { current ->
            current.copy(
                grants = current.grants - url,
                pending = current.pending - url,
                denied = current.denied - url,
            )
        }

    /** Seeds the pending queue with the app's suggested starting relays. */
    suspend fun suggest(urls: Collection<RelayUrl>) = note(urls, DiscoveryReason(DiscoverySource.BOOTSTRAP))

    /**
     * Records the relays a NIP-19 pointer named — an `nprofile`'s hints.
     *
     * Queued rather than used: a hint is a claim made by whoever wrote the link,
     * and this app has exactly one way to start talking to a relay.
     */
    suspend fun noteHint(
        urls: Collection<RelayUrl>,
        namedBy: String,
    ) = note(urls, DiscoveryReason(DiscoverySource.EVENT_HINT, "named by the link to $namedBy"))

    private suspend fun mutate(block: (RelayDirectorySnapshot) -> RelayDirectorySnapshot) {
        val updated =
            writeLock.withLock {
                val next = block(state.value)
                if (next == state.value) return
                state.value = next
                next
            }
        persistence?.save(updated)
    }
}

/** The read side of [RelayDirectory], for components that only need the verdict. */
fun interface RelayAccessPolicy {
    fun isApproved(url: RelayUrl): Boolean
}

/** Persistence for the relay directory. Implemented per platform. */
interface RelayDirectoryStore {
    suspend fun load(): RelayDirectorySnapshot

    suspend fun save(snapshot: RelayDirectorySnapshot)
}
