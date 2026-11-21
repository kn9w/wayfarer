package app.wayfarer.core.relay

import app.wayfarer.core.model.MediaDirectorySnapshot
import app.wayfarer.core.model.MediaGrant
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.MediaReason
import app.wayfarer.core.model.MediaSource
import app.wayfarer.core.model.PendingMediaHost
import app.wayfarer.core.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single authority on which media hosts this app may ask for a picture.
 *
 * The same shape as [RelayDirectory] and for the same reason. Deny by default:
 * [loadable] returns only hosts the user has granted, and every rejected one is
 * recorded in [pending] together with the reason it was wanted. Nothing in the
 * app may build a set of image URLs to fetch except by passing it through here.
 *
 * The gate is enforced a second time at the request layer — see
 * `GatedImageRequests` in the app module, an OkHttp interceptor that refuses any
 * call to a host this directory does not approve. That is the same
 * belt-and-braces the relay side uses: a bug in the code that decides which
 * pictures to show still cannot leak a request.
 *
 * Unlike relays, nothing is suggested at first run. The app ships five bootstrap
 * relays because with none of them a new user can see nothing at all; with no
 * approved media host the app works completely, just with drawn marks instead of
 * photographs. A shipped list of popular image hosts would be this app inventing
 * a list on the user's behalf, which is the thing it does not do.
 */
class MediaDirectory(
    private val clock: Clock,
    initial: MediaDirectorySnapshot = MediaDirectorySnapshot(),
    private val persistence: MediaDirectoryStore? = null,
) : MediaAccessPolicy {
    private val writeLock = Mutex()

    private val state = MutableStateFlow(initial)

    /** Whose list this currently is, or null for a session with nobody signed in. */
    private var owner: PubKey? = null

    val snapshot: StateFlow<MediaDirectorySnapshot> = state.asStateFlow()

    val grants: Map<MediaHost, MediaGrant> get() = state.value.grants

    val pending: Map<MediaHost, PendingMediaHost> get() = state.value.pending

    override fun isApproved(host: MediaHost): Boolean = state.value.grants[host]?.isApproved == true

    /**
     * Narrows [candidates] to the hosts approved for loading, recording every
     * rejected one as pending with [reason].
     *
     * The recording is the point, exactly as it is for relays: the queue on the
     * media screen fills itself as a side effect of the app drawing the profiles
     * the user actually reads, so it lists the servers their own follows use
     * rather than a set this app picked.
     */
    suspend fun loadable(
        candidates: Collection<MediaHost>,
        reason: MediaReason,
    ): Set<MediaHost> {
        if (candidates.isEmpty()) return emptySet()

        val current = state.value
        val allowed = LinkedHashSet<MediaHost>(candidates.size)
        var unknown: MutableSet<MediaHost>? = null

        for (host in candidates) {
            val grant = current.grants[host]
            when {
                grant != null && grant.isApproved -> allowed += host
                // An existing decision either way, so nothing to ask again.
                grant != null || host in current.denied -> Unit
                else -> (unknown ?: mutableSetOf<MediaHost>().also { unknown = it }) += host
            }
        }

        unknown?.let { note(it, reason) }
        return allowed
    }

    /** Records hosts as pending without asking to use them right now. */
    suspend fun note(
        hosts: Collection<MediaHost>,
        reason: MediaReason,
    ) {
        if (hosts.isEmpty()) return
        mutate { current ->
            val now = clock.nowSeconds()
            val pending = current.pending.toMutableMap()
            var changed = false
            for (host in hosts) {
                if (host in current.grants || host in current.denied) continue
                val existing = pending[host]
                val updated =
                    existing?.merge(setOf(reason), now)
                        ?: PendingMediaHost(host, setOf(reason), firstSeenAt = now, lastSeenAt = now)
                if (updated != existing) {
                    pending[host] = updated
                    changed = true
                }
            }
            if (changed) current.copy(pending = pending) else current
        }
    }

    /**
     * Allows pictures from [host] and clears it from the queue.
     *
     * Passing false is a revocation, not a denial: the host returns to the queue
     * only if some profile asks for it again.
     */
    suspend fun approve(
        host: MediaHost,
        load: Boolean,
    ) = mutate { current ->
        val grants = current.grants.toMutableMap()
        if (load) grants[host] = MediaGrant.loading(host) else grants.remove(host)
        current.copy(
            grants = grants,
            pending = current.pending - host,
            denied = current.denied - host,
        )
    }

    /** Revokes any grant and remembers the rejection so it stops re-appearing. */
    suspend fun deny(host: MediaHost) =
        mutate { current ->
            current.copy(
                grants = current.grants - host,
                pending = current.pending - host,
                denied = current.denied + host,
            )
        }

    /** Forgets a host entirely — no grant, no queue entry, no denial. */
    suspend fun forget(host: MediaHost) =
        mutate { current ->
            current.copy(
                grants = current.grants - host,
                pending = current.pending - host,
                denied = current.denied - host,
            )
        }

    /** Queues a host the user typed in themselves. */
    suspend fun noteEntered(host: MediaHost) = note(setOf(host), MediaReason(MediaSource.USER_ENTERED))

    private suspend fun mutate(block: (MediaDirectorySnapshot) -> MediaDirectorySnapshot) {
        val updated =
            writeLock.withLock {
                val next = block(state.value)
                if (next == state.value) return
                state.value = next
                next
            }
        persistence?.save(owner, updated)
    }

    /**
     * Points the list at [account]'s own, or at a fresh one for a session with
     * nobody signed in.
     *
     * The same rule as the relay permissions, for the same reason: allowing a
     * picture server is consent to hand that server your IP address and the fact
     * that you are looking at a particular profile, given by a person rather
     * than by a handset. A guest's list lives for the session and is never
     * written down.
     */
    suspend fun scopeTo(account: PubKey?) {
        writeLock.withLock {
            owner = account
            state.value = persistence?.load(account) ?: MediaDirectorySnapshot()
        }
    }

    /** Erases [account]'s list from this device. See `RelayDirectory.forget`. */
    suspend fun forget(account: PubKey) {
        persistence?.delete(account)
        if (owner == account) scopeTo(null)
    }
}

/** The read side of [MediaDirectory], for components that only need the verdict. */
fun interface MediaAccessPolicy {
    fun isApproved(host: MediaHost): Boolean
}

/** Persistence for the media directory. Implemented per platform. */
interface MediaDirectoryStore {
    suspend fun load(owner: PubKey?): MediaDirectorySnapshot

    suspend fun save(
        owner: PubKey?,
        snapshot: MediaDirectorySnapshot,
    )

    /** Erases [owner]'s record. See [MediaDirectory.forget]. */
    suspend fun delete(owner: PubKey)
}
