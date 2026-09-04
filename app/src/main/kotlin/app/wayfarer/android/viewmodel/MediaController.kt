package app.wayfarer.android.viewmodel

import app.wayfarer.android.platform.MediaUrls
import app.wayfarer.android.platform.PostMedia
import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.MediaGrant
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.MediaReason
import app.wayfarer.core.model.MediaSource
import app.wayfarer.core.model.PendingMediaHost
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.PubKey
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
     * Names a person for the reasons on the media screen.
     *
     * The reasons are the whole decision: "image.example" is not a question
     * anybody can answer, and "the picture of alice" and "a picture in a post by
     * bob" are.
     */
    private val describe: (PubKey) -> String = { it.abbreviated() },
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

    init {
        queueHostsAsTheyArrive()
    }

    /**
     * Fills the waiting list by reading, rather than by being asked to.
     *
     * This is the difference between a queue and a form. A host used to reach
     * the list only when somebody pressed the badge on a picture that had not
     * loaded — which meant the list was empty for anyone who never pressed one,
     * and the app was asking the user to nominate the servers it wanted to
     * contact. Every profile, article and post the app takes in now records the
     * hosts it points at, with the reason, and the media screen is a list of
     * what your own reading actually reached for.
     *
     * Nothing here contacts anything: recording a host is a local note, and the
     * only thing that can open a connection is a grant the user gives on that
     * screen.
     */
    private fun queueHostsAsTheyArrive() {
        // Three collectors, three separate memories of what has been handled.
        // Each set is confined to the coroutine that owns it: this scope is
        // multi-threaded, and one set shared between them would be a data race
        // on a plain HashSet.
        scope.launch {
            // Keyed by URL rather than by person: a profile is *replaced* when a
            // newer kind 0 arrives, so a pubkey already seen is not evidence
            // that the picture it now names has been.
            val handled = mutableSetOf<String>()
            core.profiles.profiles.collect { profiles ->
                for ((pubKey, profile) in profiles) {
                    profile.picture?.let {
                        if (handled.add(it)) noteHost(it, MediaSource.AVATAR, "the picture of ${describe(pubKey)}")
                    }
                    profile.banner?.let {
                        if (handled.add(it)) {
                            noteHost(it, MediaSource.BANNER, "the banner on ${describe(pubKey)}'s profile")
                        }
                    }
                }
            }
        }

        scope.launch {
            // Same reason: an article is addressable, so an edit replaces it in
            // place and may point somewhere new.
            val handled = mutableSetOf<String>()
            core.articles.all.collect { articles ->
                for (article in articles.values) {
                    article.image?.let {
                        if (handled.add(it)) {
                            noteHost(it, MediaSource.ARTICLE_IMAGE, "the header picture of \"${article.title}\"")
                        }
                    }
                    for (media in MediaUrls.mediaIn(article.content)) {
                        if (handled.add(media.url)) {
                            noteHost(media.url, MediaSource.POST_IMAGE, "a picture in \"${article.title}\"")
                        }
                    }
                }
            }
        }

        scope.launch {
            // Notes are keyed by id, which is the one thing here that cannot
            // change: an event's content is fixed by its signature. The size
            // check skips the common emission that merely merged a second
            // relay's copy of a note already read.
            val handled = mutableSetOf<EventId>()
            core.feed.allNotes.collect { notes ->
                if (notes.size == handled.size) return@collect
                for (note in notes.values) {
                    if (!handled.add(note.id)) continue
                    val because = "a picture in a post by ${describe(note.author)}"
                    for (media in MediaUrls.mediaIn(note.content)) {
                        noteHost(media.url, MediaSource.POST_IMAGE, because)
                    }
                }
            }
        }
    }

    private suspend fun noteHost(
        url: String?,
        source: MediaSource,
        because: String,
    ) {
        val host = MediaUrls.hostOf(url) ?: return
        core.mediaDirectory.note(setOf(host), MediaReason(source, because))
    }

    /** Every picture and video a post's text points at, in the order it appears. */
    fun mediaIn(content: String): List<PostMedia> = MediaUrls.mediaIn(content)

    /**
     * The host [raw] names, or null when it names none.
     *
     * The same parse [add] does, without doing it — so a screen can say what is
     * wrong with what was typed, next to where it was typed, before anything is
     * queued.
     */
    fun hostOf(raw: String): MediaHost? = MediaUrls.hostOf(raw) ?: MediaHost.parseOrNull(raw.trim())

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
            val host = hostOf(raw)
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
