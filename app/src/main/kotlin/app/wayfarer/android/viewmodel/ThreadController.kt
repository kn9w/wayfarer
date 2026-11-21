package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.ThreadRef
import app.wayfarer.core.repo.PublishResult
import app.wayfarer.core.repo.ThreadEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What is known about one conversation. */
data class ThreadState(
    val entries: List<ThreadEntry> = emptyList(),
    val loading: Boolean = false,
    /**
     * Whether the thread has ever been fetched.
     *
     * The difference between "no replies" and "not asked yet", which the UI has
     * to be able to tell apart: claiming a count before looking would be a lie,
     * and looking for every note on screen would be a query per note.
     */
    val loaded: Boolean = false,
    val posting: Boolean = false,
)

/**
 * Conversations, and which of them are open.
 *
 * Threads are fetched when a reader opens one, never in advance. A reply count
 * shown next to every post in a feed would cost one relay query per post, which
 * is the exact mistake that made the streamed feed stall before it was batched.
 */
class ThreadController(
    private val core: Wayfarer,
    private val scope: CoroutineScope,
    private val report: (UserMessage) -> Unit,
) {
    private val expandedState = MutableStateFlow<Set<ThreadRef>>(emptySet())
    private val threadsState = MutableStateFlow<Map<ThreadRef, ThreadState>>(emptyMap())
    private val collapsedState = MutableStateFlow<Set<EventId>>(emptySet())

    /** The roots whose threads are open on screen. */
    val expanded: StateFlow<Set<ThreadRef>> = expandedState.asStateFlow()

    val threads: StateFlow<Map<ThreadRef, ThreadState>> = threadsState.asStateFlow()

    /**
     * Replies whose own sub-replies are folded away.
     *
     * Held by event id rather than per root, because an entry belongs to exactly
     * one conversation — and held here rather than in the Composable so that
     * scrolling a long thread out of composition does not silently unfold it.
     */
    val collapsed: StateFlow<Set<EventId>> = collapsedState.asStateFlow()

    /** Folds or unfolds everything written under one reply. */
    fun toggleCollapsed(id: EventId) {
        val current = collapsedState.value
        collapsedState.value = if (id in current) current - id else current + id
    }

    fun stateOf(root: ThreadRef): ThreadState = threadsState.value[root] ?: ThreadState()

    fun isExpanded(root: ThreadRef): Boolean = root in expandedState.value

    /** Opens or closes a thread, fetching it the first time it is opened. */
    fun toggle(root: ThreadRef) {
        if (root in expandedState.value) {
            expandedState.value = expandedState.value - root
            return
        }
        expandedState.value = expandedState.value + root
        if (!stateOf(root).loaded) load(root)
    }

    fun open(root: ThreadRef) {
        if (root !in expandedState.value) toggle(root)
    }

    /** Fetches, or refetches, one conversation. */
    fun load(root: ThreadRef) {
        if (stateOf(root).loading) return
        update(root) { it.copy(loading = true) }
        scope.launch {
            try {
                core.threads.load(root)
                update(root) { it.copy(entries = core.threads.threadUnder(root), loading = false, loaded = true) }
            } catch (failure: Throwable) {
                // A conversation that would not load is not worth a banner over
                // the whole app; the thread simply stays empty and can be retried.
                update(root) { it.copy(loading = false, loaded = true) }
            }
        }
    }

    /**
     * Publishes a reply.
     *
     * [parent] is what is being answered and [root] what the thread is about.
     * For a reply to the post itself the two are the same; for a reply to
     * somebody else's reply they differ, and NIP-22 wants both.
     */
    fun reply(
        root: ThreadRef,
        rootKind: String,
        rootAuthor: PubKey?,
        parent: ThreadRef,
        parentKind: String,
        parentAuthor: PubKey?,
        content: String,
    ) {
        val me = core.accounts.account.value
        val signer = core.accounts.signer
        if (me == null || signer == null) {
            report(UserMessage.Error("Replying needs an account."))
            return
        }
        if (content.isBlank()) return

        update(root) { it.copy(posting = true) }
        scope.launch {
            try {
                val result =
                    core.threads.comment(
                        signer = signer,
                        author = me.pubKey,
                        root = root,
                        rootKind = rootKind,
                        rootAuthor = rootAuthor,
                        parent = parent,
                        parentKind = parentKind,
                        parentAuthor = parentAuthor,
                        content = content,
                    )
                when (result) {
                    is PublishResult.Success -> report(UserMessage.Published(result.report))
                    is PublishResult.Failure -> report(result.error.toMessage())
                }
                update(root) { it.copy(entries = core.threads.threadUnder(root), posting = false, loaded = true) }
            } catch (failure: Throwable) {
                report(UserMessage.Error(failure.message ?: "Could not post the reply"))
                update(root) { it.copy(posting = false) }
            }
        }
    }

    /** Drops every cached thread. Used when the account changes. */
    fun clear() {
        expandedState.value = emptySet()
        threadsState.value = emptyMap()
    }

    private fun update(
        root: ThreadRef,
        block: (ThreadState) -> ThreadState,
    ) {
        threadsState.value = threadsState.value + (root to block(stateOf(root)))
    }
}

/** The thread a note or article is the root of. */
fun rootRefOfNote(id: EventId): ThreadRef = ThreadRef.Event(id)

fun rootRefOfArticle(address: String): ThreadRef = ThreadRef.Address(address)
