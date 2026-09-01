package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.PendingRelay
import app.wayfarer.core.model.RelayGrant
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.outbox.publishersByRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the relay settings screen renders. */
data class RelayScreenState(
    val approved: List<RelayGrant> = emptyList(),
    val pending: List<PendingRelay> = emptyList(),
    val denied: List<RelayUrl> = emptyList(),
    /**
     * How many people are known to publish at each relay.
     *
     * The ranking signal for the whole screen. A relay forty of your follows post
     * to is a different decision from one a single author named once, and after a
     * few hundred queue up that ordering is what keeps the list navigable.
     */
    val publisherCounts: Map<RelayUrl, Int> = emptyMap(),
    /** Relays the user starred. Offered first, here and in the Global picker. */
    val favourites: Set<RelayUrl> = emptySet(),
) {
    fun publishersAt(url: RelayUrl): Int = publisherCounts[url] ?: 0

    fun isFavourite(url: RelayUrl): Boolean = url in favourites
}

/**
 * The relay permission screen's state and actions.
 *
 * Every mutation goes straight to `RelayDirectory`; this class holds no policy of
 * its own, which is the point — there is exactly one place in the app that
 * decides what a relay is allowed to do.
 *
 * This list is local to this app. It is not a NIP-65 relay list, changing it
 * publishes nothing, and no event is written when a relay is approved or
 * blocked. Advertising relays to the network is a different list with different
 * meaning, kept in a different view model — see [RelayListController].
 */
class RelayController(
    private val core: Wayfarer,
    private val scope: CoroutineScope,
    private val report: (UserMessage) -> Unit,
    /**
     * Called after any permission change.
     *
     * A grant is not bookkeeping: it changes what the app is able to fetch, so
     * whatever is on screen has to be reloaded against the new permissions or
     * the user is left to discover for themselves that they must go and press
     * Refresh.
     */
    private val onChanged: () -> Unit = {},
) {
    val state: StateFlow<RelayScreenState> =
        combine(core.relayDirectory.snapshot, core.relayLists.lists) { snapshot, lists ->
            val counts = publishersByRelay(lists).mapValues { (_, authors) -> authors.size }
            fun count(url: RelayUrl) = counts[url] ?: 0

            fun starred(url: RelayUrl) = url in snapshot.favourites

            RelayScreenState(
                // Starred first, then busiest. A star is the user saying this one
                // matters to them, which outranks any count the app derived; ties
                // fall back to the old orders, keeping the sort stable as counts
                // come and go.
                approved =
                    snapshot.grants.values.sortedWith(
                        compareByDescending<RelayGrant> { starred(it.url) }
                            .thenByDescending { count(it.url) }
                            .thenBy { it.url.display() },
                    ),
                pending =
                    snapshot.pending.values.sortedWith(
                        compareByDescending<PendingRelay> { starred(it.url) }
                            .thenByDescending { count(it.url) }
                            .thenByDescending { it.lastSeenAt },
                    ),
                denied = snapshot.denied.sorted(),
                publisherCounts = counts,
                favourites = snapshot.favourites,
            )
        }.stateIn(scope, SharingStarted.Eagerly, RelayScreenState())

    fun setPermissions(
        url: RelayUrl,
        read: Boolean,
        write: Boolean,
    ) = scope.launch {
        core.relayDirectory.approve(url, read, write)
        onChanged()
    }

    fun deny(url: RelayUrl) =
        scope.launch {
            core.relayDirectory.deny(url)
            onChanged()
        }

    /**
     * Stars or unstars a relay.
     *
     * Pointedly does not call [onChanged]: a star changes nothing about what may
     * be fetched, and reloading the feed over it would be work the user did not
     * ask for in response to an act that means nothing to the network.
     */
    fun setFavourite(
        url: RelayUrl,
        favourite: Boolean,
    ) = scope.launch {
        core.relayDirectory.setFavourite(url, favourite)
    }

    fun forget(url: RelayUrl) =
        scope.launch {
            core.relayDirectory.forget(url)
            onChanged()
        }

    fun add(
        raw: String,
        read: Boolean,
        write: Boolean,
    ) = scope.launch {
        if (core.addRelay(raw, read, write) == null) {
            report(UserMessage.Error("\"$raw\" is not a relay address. Try something like wss://relay.example.com"))
        } else {
            onChanged()
        }
    }
}
