package app.wayfarer.core.repo

import app.wayfarer.core.model.PubKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Everyone the reader follows, by either route.
 *
 * Two lists with different consequences — a kind 3 follow is public and signed,
 * a local one is never published — and one answer to "whose posts do I want",
 * which is what the feed and the router actually ask. Nothing merges the lists
 * on disk; the union is computed, so removing a follow from one never silently
 * rewrites the other.
 *
 * Note what the union costs, because it is the limit of what "local" buys. This
 * is the set the router turns into `authors` filters, so every pubkey on it is
 * named to the relays the app reads from, whichever list it came from. A local
 * follow is hidden from other people's clients; it is not hidden from the relay
 * answering the query. Closing that would take per-author connection isolation,
 * which this app does not do — so the UI says so rather than implying otherwise.
 *
 * A [Flow] and a plain getter rather than a derived [kotlinx.coroutines.flow.StateFlow]
 * because [app.wayfarer.core.Wayfarer] holds no scope to share one in, and those
 * are exactly the two shapes the callers need: the Global screen folds
 * [combined] into its own state, everything else reads [now].
 */
class FollowBook(
    private val contacts: ContactRepository,
    private val local: LocalFollowStore,
) {
    val combined: Flow<Set<PubKey>> =
        combine(contacts.follows, local.follows) { published, here -> published + here }

    val now: Set<PubKey> get() = contacts.follows.value + local.follows.value

    /** Which list, or lists, a person is on. Drives what unfollowing them means. */
    fun sourcesOf(pubKey: PubKey): Set<FollowSource> =
        buildSet {
            if (pubKey in contacts.follows.value) add(FollowSource.Published)
            if (pubKey in local.follows.value) add(FollowSource.Local)
        }
}

/** Where a follow lives. A person can be on both lists at once. */
enum class FollowSource {
    /** A kind 3 p-tag: public, signed, and visible to every other client. */
    Published,

    /**
     * This phone's own list. Never published as an event, so no other client
     * sees it — but see [FollowBook]: the relays queried still learn the names.
     */
    Local,
}
