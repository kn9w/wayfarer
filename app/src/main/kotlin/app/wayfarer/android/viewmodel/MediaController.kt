package app.wayfarer.android.viewmodel

import app.wayfarer.android.platform.MediaUrls
import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.MediaGrant
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.PendingMediaHost
import app.wayfarer.core.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the media permission screen renders. */
data class MediaScreenState(
    val approved: List<MediaGrant> = emptyList(),
    val pending: List<PendingMediaHost> = emptyList(),
    val denied: List<MediaHost> = emptyList(),
    /**
     * How many people whose profile this app has seen keep a picture at each host.
     *
     * The ranking signal for the whole screen, and the media counterpart of the
     * relay screen's "N people publish here". A host forty of the people you read
     * use is a different decision from one a single profile named once, and with
     * a queue that grows by one entry per unusual avatar that ordering is what
     * keeps the list answerable.
     */
    val userCounts: Map<MediaHost, Int> = emptyMap(),
) {
    fun usersAt(host: MediaHost): Int = userCounts[host] ?: 0

    /**
     * What this app currently permits for [host].
     *
     * [MediaApproval.Unknown] is a real answer rather than an absence: a host
     * named by a profile nothing has tried to draw yet is in none of the three
     * lists, and "not decided" is what an avatar needs to show.
     */
    fun approvalOf(host: MediaHost): MediaApproval =
        when {
            approved.any { it.host == host } -> MediaApproval.Allowed
            pending.any { it.host == host } -> MediaApproval.Waiting
            host in denied -> MediaApproval.Blocked
            else -> MediaApproval.Unknown
        }
}

/** What the app permits for one media host, as a single answer. */
enum class MediaApproval { Allowed, Waiting, Blocked, Unknown }

/**
 * The media permission screen's state and actions.
 *
 * Every mutation goes straight to `MediaDirectory`; this class holds no policy of
 * its own, for the same reason [RelayController] holds none — there is exactly
 * one place in the app that decides what a media host is allowed to do.
 *
 * This list is local to this app. Nothing about it is published, no event is
 * written when a host is allowed or blocked, and no server is told it was chosen.
 */
class MediaController(
    private val core: Wayfarer,
    private val scope: CoroutineScope,
    private val report: (UserMessage) -> Unit,
    /**
     * Called after any permission change.
     *
     * A grant changes what can be drawn, so whatever is on screen has to be
     * recomposed against it — otherwise allowing a host leaves the user looking
     * at the same drawn mark, with nothing to say the decision took effect.
     */
    private val onChanged: () -> Unit = {},
) {
    val state: StateFlow<MediaScreenState> =
        combine(core.mediaDirectory.snapshot, core.profiles.profiles) { snapshot, profiles ->
            val counts = usersByHost(profiles.values)
            fun count(host: MediaHost) = counts[host] ?: 0

            MediaScreenState(
                // Busiest first within each group, then alphabetical, which keeps
                // the sort stable as counts come and go.
                approved =
                    snapshot.grants.values.sortedWith(
                        compareByDescending<MediaGrant> { count(it.host) }.thenBy { it.host.display() },
                    ),
                pending =
                    snapshot.pending.values.sortedWith(
                        compareByDescending<PendingMediaHost> { count(it.host) }.thenByDescending { it.lastSeenAt },
                    ),
                denied = snapshot.denied.sorted(),
                userCounts = counts,
            )
        }.stateIn(scope, SharingStarted.Eagerly, MediaScreenState())

    fun allow(host: MediaHost) =
        scope.launch {
            core.mediaDirectory.approve(host, load = true)
            onChanged()
        }

    fun revoke(host: MediaHost) =
        scope.launch {
            core.mediaDirectory.approve(host, load = false)
            onChanged()
        }

    fun deny(host: MediaHost) =
        scope.launch {
            core.mediaDirectory.deny(host)
            onChanged()
        }

    fun forget(host: MediaHost) =
        scope.launch {
            core.mediaDirectory.forget(host)
            onChanged()
        }

    /** Queues a host the user typed in. Never approves it — that is a second tap. */
    fun add(raw: String) =
        scope.launch {
            val host = MediaUrls.hostOf(raw) ?: MediaHost.parseOrNull(raw)
            if (host == null) {
                report(UserMessage.Error("That is not a server address."))
            } else {
                core.mediaDirectory.noteEntered(host)
            }
        }
}

/**
 * How many of the profiles this app has seen keep a picture at each host.
 *
 * A person is counted once per host however many of their images live there, so
 * the number reads as "people", which is the unit the decision is actually made
 * in.
 */
internal fun usersByHost(profiles: Collection<Profile>): Map<MediaHost, Int> {
    val counts = mutableMapOf<MediaHost, Int>()
    for (profile in profiles) {
        val hosts = listOfNotNull(MediaUrls.hostOf(profile.picture), MediaUrls.hostOf(profile.banner)).toSet()
        for (host in hosts) counts[host] = (counts[host] ?: 0) + 1
    }
    return counts
}
