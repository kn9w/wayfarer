package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.PendingRelay
import app.wayfarer.core.model.RelayGrant
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayListEntry
import app.wayfarer.core.repo.PublishResult
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
 */
class RelayController(
    private val core: Wayfarer,
    private val scope: CoroutineScope,
    private val report: (UserMessage) -> Unit,
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
    ) = scope.launch { core.relayDirectory.approve(url, read, write) }

    fun deny(url: RelayUrl) = scope.launch { core.relayDirectory.deny(url) }

    fun forget(url: RelayUrl) = scope.launch { core.relayDirectory.forget(url) }

    fun add(
        raw: String,
        read: Boolean,
        write: Boolean,
    ) = scope.launch {
        if (core.addRelay(raw, read, write) == null) {
            report(UserMessage.Error("\"$raw\" is not a relay address. Try something like wss://relay.example.com"))
        }
    }

    /**
     * Publishes the account's kind 10002 from the current grants: every relay
     * approved for reading is advertised as a read relay, every one approved for
     * writing as a write relay.
     *
     * This is what makes the permission screen mean something to the rest of the
     * network — it is how other people's clients learn where to find this user.
     */
    fun publishRelayList() =
        scope.launch {
            val me = core.accounts.account.value
            val signer = core.accounts.signer
            if (me == null || signer == null || !signer.canSign) {
                report(UserMessage.Error("Sign in with an nsec to publish a relay list."))
                return@launch
            }

            val entries =
                core.relayDirectory.grants.values
                    .filter { it.isApproved }
                    .map { RelayListEntry(it.url, read = it.read, write = it.write) }

            if (entries.isEmpty()) {
                report(UserMessage.Error("Approve at least one relay first."))
                return@launch
            }

            when (val result = core.relayListRepo.publishOwn(signer, me.pubKey, entries)) {
                is PublishResult.Success -> report(UserMessage.Published(result.report))
                is PublishResult.Failure -> report(UserMessage.Error("Could not publish the relay list: ${result.error}"))
            }
        }
}
