package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.PendingRelay
import app.wayfarer.core.model.RelayGrant
import app.wayfarer.core.model.RelayUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the relay settings screen renders. */
data class RelayScreenState(
    val approved: List<RelayGrant> = emptyList(),
    val pending: List<PendingRelay> = emptyList(),
    val denied: List<RelayUrl> = emptyList(),
)

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
        core.relayDirectory.snapshot
            .map { snapshot ->
                RelayScreenState(
                    approved = snapshot.grants.values.sortedBy { it.url.display() },
                    pending = snapshot.pending.values.sortedByDescending { it.lastSeenAt },
                    denied = snapshot.denied.sorted(),
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
