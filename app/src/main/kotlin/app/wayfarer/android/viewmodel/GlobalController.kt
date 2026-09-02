package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.Article
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayDirectorySnapshot
import app.wayfarer.core.model.RelayUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.random.Random

/** Which of the two things the Global screen is showing. */
enum class BrowseMode { Follows, Relay }

/** How the thing the arrows step through is ordered. */
enum class BrowseOrder { Chronological, Random }

/**
 * Which follows are in the rotation at all.
 *
 * Judged from the posts Wayfarer has actually fetched — a relay hands out a
 * bounded window, and somebody posting only to relays this app may not read is
 * indistinguishable from somebody who has stopped. The UI says so rather than
 * implying it knows.
 */
enum class ActivityFilter { Any, ActiveRecently, QuietRecently }

/**
 * What the Global screen renders.
 *
 * The two modes are deliberately not the same shape. Follows steps through
 * *people*, showing everything one person wrote; Relay steps through *posts*,
 * one at a time, from a single relay. One is catching up with somebody, the
 * other is looking at what a stranger's server is carrying, and collapsing them
 * into one list would misrepresent both.
 */
data class GlobalState(
    val mode: BrowseMode = BrowseMode.Follows,
    val order: BrowseOrder = BrowseOrder.Chronological,
    val activity: ActivityFilter = ActivityFilter.Any,
    /** The people the arrows step through, already filtered and ordered. */
    val rotation: List<PubKey> = emptyList(),
    val person: PubKey? = null,
    /** Everything [person] wrote, newest first. */
    val personPosts: List<FeedItem> = emptyList(),
    /** Approved read relays, for the picker. */
    val relays: List<RelayUrl> = emptyList(),
    val relay: RelayUrl? = null,
    /** [relay]'s posts, in [order]. */
    val relayPosts: List<FeedItem> = emptyList(),
    val postIndex: Int = 0,
    /** How many follows the activity filter is currently hiding. */
    val hiddenByActivity: Int = 0,
) {
    /** The post Relay mode is showing, or null when there is nothing to show. */
    val currentPost: FeedItem? get() = relayPosts.getOrNull(postIndex)

    private val personIndex: Int get() = rotation.indexOf(person)

    val hasPrevious: Boolean
        get() =
            when (mode) {
                BrowseMode.Follows -> personIndex > 0
                BrowseMode.Relay -> postIndex > 0
            }

    val hasNext: Boolean
        get() =
            when (mode) {
                BrowseMode.Follows -> personIndex >= 0 && personIndex < rotation.lastIndex
                BrowseMode.Relay -> postIndex < relayPosts.lastIndex
            }

    /** "3 of 40" — where the arrows have got to. */
    val position: String
        get() =
            when (mode) {
                BrowseMode.Follows -> if (rotation.isEmpty()) "" else "${personIndex + 1} of ${rotation.size}"
                BrowseMode.Relay -> if (relayPosts.isEmpty()) "" else "${postIndex + 1} of ${relayPosts.size}"
            }
}

/**
 * The Global screen's state and actions.
 *
 * Holds no data of its own: the rotation, the posts and the recency it filters
 * by are all derived from caches the repositories already keep, so nothing here
 * can drift out of step with what the feed actually has.
 */
class GlobalController(
    private val core: Wayfarer,
    scope: CoroutineScope,
    /**
     * Called when the screen needs data it does not have — a new relay selected,
     * or a person paged to with nothing cached. Kept as a callback so this class
     * never talks to the transport itself.
     */
    private val onSubjectChanged: () -> Unit = {},
) {
    /**
     * Null until the user picks one.
     *
     * The default cannot be a constant: Follows mode is empty for somebody who
     * follows nobody, which is every new account and every guest, and dropping
     * them on an empty rotation would look like the app failing to load. Until
     * they choose, the mode follows from whether they have anyone to read.
     */
    private val chosenMode = MutableStateFlow<BrowseMode?>(null)
    private val orderState = MutableStateFlow(BrowseOrder.Chronological)
    private val activityState = MutableStateFlow(ActivityFilter.Any)

    /**
     * Fixed until something re-rolls it.
     *
     * A shuffle recomputed on every emission would reorder the list under the
     * reader's thumb each time a note streamed in, which is not "random order",
     * it is the screen refusing to hold still.
     */
    private val seedState = MutableStateFlow(Random.nextLong())

    /** Held by identity rather than index, so an arrival cannot move the cursor. */
    private val personState = MutableStateFlow<PubKey?>(null)
    private val relayState = MutableStateFlow<RelayUrl?>(null)
    private val postKeyState = MutableStateFlow<String?>(null)

    val state: StateFlow<GlobalState> =
        combine(
            combine(chosenMode, orderState, activityState, seedState, ::Selection),
            combine(personState, relayState, postKeyState, ::Cursor),
            // The relay grants belong in here rather than being read at build
            // time: allowing or blocking a relay changes the picker, and a plain
            // read would leave the screen showing a relay the user just revoked.
            combine(
                core.contacts.follows,
                core.feed.allNotes,
                core.articles.all,
                core.relayDirectory.snapshot,
            ) { follows, notes, articles, directory ->
                Sources(follows, notes.values.toList(), articles.values.toList(), directory)
            },
            core.preferences.activityWindowDays,
            ::build,
        ).stateIn(scope, SharingStarted.Eagerly, GlobalState())

    private data class Selection(
        val chosenMode: BrowseMode?,
        val order: BrowseOrder,
        val activity: ActivityFilter,
        val seed: Long,
    )

    private data class Cursor(
        val person: PubKey?,
        val relay: RelayUrl?,
        val postKey: String?,
    )

    private data class Sources(
        val follows: Set<PubKey>,
        val notes: List<Note>,
        val articles: List<Article>,
        val directory: RelayDirectorySnapshot,
    )

    private fun build(
        selection: Selection,
        cursor: Cursor,
        sources: Sources,
        activityWindowDays: Int,
    ): GlobalState {
        val follows = sources.follows
        val notes = sources.notes
        val articles = sources.articles

        val rotation =
            rotationFrom(
                follows, notes, articles,
                selection.activity, selection.order, selection.seed,
                daysToSeconds(activityWindowDays),
            )
        val hidden = follows.size - rotation.size

        // Keep the reader where they are if that person survived the filter;
        // otherwise fall to the front rather than to an empty screen.
        val person = cursor.person?.takeIf { it in rotation } ?: rotation.firstOrNull()

        val relays = readRelaysIn(sources.directory)

        val relay = cursor.relay?.takeIf { it in relays } ?: relays.firstOrNull()

        val relayPosts =
            if (relay == null) {
                emptyList()
            } else {
                orderPosts(
                    mergeNewestFirst(notes.filter { relay in it.seenOn }, articles.filter { relay in it.seenOn }),
                    selection,
                )
            }
        // Same rule as the person cursor: pinned to the post itself, so a note
        // arriving mid-read does not shuffle the one on screen out from under it.
        val postIndex = relayPosts.indexOfFirst { it.key == cursor.postKey }.takeIf { it >= 0 } ?: 0

        return GlobalState(
            mode = selection.chosenMode ?: defaultModeFor(follows),
            order = selection.order,
            activity = selection.activity,
            rotation = rotation,
            person = person,
            personPosts =
                if (person == null) {
                    emptyList()
                } else {
                    mergeNewestFirst(notes.filter { it.author == person }, articles.filter { it.author == person })
                },
            relays = relays,
            relay = relay,
            relayPosts = relayPosts,
            postIndex = postIndex,
            hiddenByActivity = hidden,
        )
    }

    /**
     * Who is in the rotation, filtered and ordered.
     *
     * Shared with the synchronous getters below rather than read back off
     * [state]: `stateIn` publishes on the next scheduler pass, so a loader that
     * consulted the flow immediately after a permission or follow changed would
     * still see the previous subject and fetch the wrong thing.
     */
    private fun rotationFrom(
        follows: Set<PubKey>,
        notes: Collection<Note>,
        articles: Collection<Article>,
        activity: ActivityFilter,
        order: BrowseOrder,
        seed: Long,
        windowSeconds: Long,
    ): List<PubKey> {
        val recency = latestPostByAuthor(notes, articles)
        val now = core.clock.nowSeconds()
        val eligible = follows.filter { activity.accepts(recency[it], now, windowSeconds) }
        return when (order) {
            // Whoever wrote most recently first — the closest this shape gets to
            // "what is new", given the arrows step through people.
            BrowseOrder.Chronological ->
                eligible.sortedWith(compareByDescending<PubKey> { recency[it] ?: Long.MIN_VALUE }.thenBy { it.hex })
            BrowseOrder.Random -> eligible.sortedBy { it.hex }.shuffled(Random(seed))
        }
    }

    private fun approvedReadRelaysNow(): List<RelayUrl> = readRelaysIn(core.relayDirectory.snapshot.value)

    /**
     * The relays available to read from, starred ones first.
     *
     * The order is the picker's order and also decides the default selection, so
     * starring a relay both floats it to the top of the list and makes it the one
     * Relay mode opens on.
     */
    private fun readRelaysIn(directory: RelayDirectorySnapshot): List<RelayUrl> =
        directory.grants.values
            .filter { it.read }
            .map { it.url }
            .sortedWith(compareByDescending<RelayUrl> { it in directory.favourites }.thenBy { it.url })

    private fun orderPosts(
        posts: List<FeedItem>,
        selection: Selection,
    ): List<FeedItem> =
        when (selection.order) {
            BrowseOrder.Chronological -> posts
            BrowseOrder.Random -> posts.sortedBy { it.key }.shuffled(Random(selection.seed))
        }

    // ---- actions ----------------------------------------------------------

    fun setMode(mode: BrowseMode) {
        if (currentMode == mode && chosenMode.value != null) return
        chosenMode.value = mode
        onSubjectChanged()
    }

    fun setOrder(order: BrowseOrder) {
        orderState.value = order
        // A fresh shuffle each time Random is chosen, so picking it again is a
        // way to re-roll rather than a no-op.
        if (order == BrowseOrder.Random) seedState.value = Random.nextLong()
    }

    fun setActivity(filter: ActivityFilter) {
        activityState.value = filter
    }

    fun selectRelay(url: RelayUrl) {
        if (relayState.value == url) return
        relayState.value = url
        postKeyState.value = null
        onSubjectChanged()
    }

    /** Re-rolls the shuffle. Called by pull-to-refresh. */
    fun reshuffle() {
        seedState.value = Random.nextLong()
    }

    fun next() = step(1)

    fun previous() = step(-1)

    private fun step(delta: Int) {
        val current = state.value
        when (current.mode) {
            BrowseMode.Follows -> {
                val at = current.rotation.indexOf(current.person)
                val next = current.rotation.getOrNull(at + delta) ?: return
                personState.value = next
                onSubjectChanged()
            }
            BrowseMode.Relay -> {
                val next = current.relayPosts.getOrNull(current.postIndex + delta) ?: return
                postKeyState.value = next.key
            }
        }
    }

    /** The person Follows mode is on, for the loader to top up. */
    val currentPerson: PubKey?
        get() {
            val rotation =
                rotationFrom(
                    core.contacts.follows.value,
                    core.feed.allNotes.value.values,
                    core.articles.all.value.values,
                    activityState.value,
                    orderState.value,
                    seedState.value,
                    daysToSeconds(core.preferences.activityWindowDays.value),
                )
            return personState.value?.takeIf { it in rotation } ?: rotation.firstOrNull()
        }

    /** The relay Relay mode is reading, for the loader and the subscription. */
    val currentRelay: RelayUrl?
        get() {
            val relays = approvedReadRelaysNow()
            return relayState.value?.takeIf { it in relays } ?: relays.firstOrNull()
        }

    val currentMode: BrowseMode
        get() = chosenMode.value ?: defaultModeFor(core.contacts.follows.value)
}

/** Relay mode for somebody with nobody to read; Follows once they have someone. */
private fun defaultModeFor(follows: Set<PubKey>): BrowseMode =
    if (follows.isEmpty()) BrowseMode.Relay else BrowseMode.Follows

/**
 * The newest thing each author is known to have written.
 *
 * Pure, and the only input the activity filter has: it is derived from what has
 * been fetched, never from a claim about what exists.
 */
fun latestPostByAuthor(
    notes: Collection<Note>,
    articles: Collection<Article>,
): Map<PubKey, Long> =
    buildMap {
        fun keepNewest(
            author: PubKey,
            at: Long,
        ) {
            val known = this[author]
            if (known == null || at > known) put(author, at)
        }
        for (note in notes) keepNewest(note.author, note.createdAt)
        for (article in articles) keepNewest(article.author, article.publishedAt)
    }

fun ActivityFilter.accepts(
    latestPostAt: Long?,
    now: Long,
    /** How long somebody may be silent and still count as active. */
    windowSeconds: Long,
): Boolean =
    when (this) {
        ActivityFilter.Any -> true
        // Nothing fetched at all is not evidence of activity, so an author we
        // have never seen a post from is quiet rather than active.
        ActivityFilter.ActiveRecently -> latestPostAt != null && now - latestPostAt <= windowSeconds
        ActivityFilter.QuietRecently -> latestPostAt == null || now - latestPostAt > windowSeconds
    }

fun daysToSeconds(days: Int): Long = days.toLong() * 24 * 60 * 60
