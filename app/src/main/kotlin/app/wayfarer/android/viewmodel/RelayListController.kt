package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayListEntry
import app.wayfarer.core.repo.PublishError
import app.wayfarer.core.repo.PublishResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One entry of the relay list being edited, with what this device thinks of it.
 *
 * [allowedHere] is the join between the two lists that must not be confused for
 * each other: advertising a relay you have not allowed is legal and sometimes
 * deliberate, but it means telling people to reach you somewhere this app will
 * not look, so the screen says so rather than quietly reconciling them.
 */
data class RelayListRow(
    val url: RelayUrl,
    /** NIP-65 `read`: where this account wants to be *reached*. Its inbox. */
    val read: Boolean,
    /** NIP-65 `write`: where this account's posts go. Its outbox. */
    val write: Boolean,
    val allowedHere: Boolean,
)

/** What the "where to find me" screen renders. */
data class RelayListState(
    val rows: List<RelayListRow> = emptyList(),
    /** `created_at` of the kind 10002 last seen for this account, or null if none. */
    val publishedAt: Long? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /**
     * True when the rows are a *suggestion* built from the relays allowed on this
     * device, because nothing has ever been published. The screen must not let
     * that read as "this is what the network knows about you".
     */
    val isSuggestion: Boolean = false,
    /** Edited since it was loaded or last published. */
    val edited: Boolean = false,
    /** False for a watch-only account: it can be looked at, not published. */
    val canPublish: Boolean = false,
)

/**
 * The account's own NIP-65 relay list (kind 10002) — a public event, and a
 * different thing from the local permission list in every way that matters.
 *
 * The permission list answers "what may this app connect to?", lives on this
 * device, and is nobody else's business. This answers "where should people look
 * for me, and where should they send things?", is a signed event on the network,
 * and is the only reason anyone who does not already share a relay with this
 * user can find them.
 *
 * They are kept apart deliberately, down to being separate view models: the one
 * automatic bridge between them — publishing whatever happened to be approved —
 * is what made the two feel like one setting, and it published relay names the
 * user had never decided to make public.
 */
class RelayListController(
    private val core: Wayfarer,
    private val scope: CoroutineScope,
    private val report: (UserMessage) -> Unit,
    /** Called after a successful publish, so prompts about the missing list can stop. */
    private val onPublished: () -> Unit = {},
) {
    private val state = MutableStateFlow(RelayListState())

    val relayList: StateFlow<RelayListState> = state.asStateFlow()

    /**
     * Fetches the account's published list and shows it as the starting draft.
     *
     * With nothing published there is nothing to show, so the relays allowed on
     * this device are offered as a first draft — marked as a suggestion, and
     * still nothing until the user publishes it.
     */
    fun load() =
        scope.launch {
            val me = core.accounts.account.value
            if (me == null) {
                state.value = RelayListState(loaded = true)
                return@launch
            }

            state.value = state.value.copy(loading = true, canPublish = me.canSign)
            val published = runCatching { core.relayListRepo.refresh(me.pubKey) }.getOrNull()

            val entries =
                published?.entries
                    ?: core.relayDirectory.grants.values
                        .filter { it.isApproved }
                        .map { RelayListEntry(it.url, read = it.read, write = it.write) }

            state.value =
                RelayListState(
                    rows = entries.toRows(),
                    publishedAt = published?.createdAt,
                    loading = false,
                    loaded = true,
                    isSuggestion = published == null,
                    edited = false,
                    canPublish = me.canSign,
                )
        }

    fun setPermissions(
        url: RelayUrl,
        read: Boolean,
        write: Boolean,
    ) {
        val rows =
            if (!read && !write) {
                state.value.rows.filterNot { it.url == url }
            } else {
                state.value.rows.map { if (it.url == url) it.copy(read = read, write = write) else it }
            }
        state.value = state.value.copy(rows = rows, edited = true)
    }

    fun remove(url: RelayUrl) {
        state.value = state.value.copy(rows = state.value.rows.filterNot { it.url == url }, edited = true)
    }

    fun add(
        raw: String,
        read: Boolean,
        write: Boolean,
    ) {
        val url = core.normalizer.normalize(raw)
        if (url == null) {
            report(UserMessage.Error("\"$raw\" is not a relay address. Try something like wss://relay.example.com"))
            return
        }
        val existing = state.value.rows.filterNot { it.url == url }
        state.value =
            state.value.copy(
                rows = (existing + RelayListRow(url, read, write, core.relayDirectory.isApproved(url))).sortedBy { it.url.display() },
                edited = true,
            )
    }

    /** Allows a relay on this device too, for a row that names one this app may not reach. */
    fun allowHere(url: RelayUrl) =
        scope.launch {
            val row = state.value.rows.firstOrNull { it.url == url } ?: return@launch
            // Direct mapping, not a mirror: a relay you advertise as `read` is one
            // you read from, and a relay you advertise as `write` is one you post to.
            core.relayDirectory.approve(url, read = row.read, write = row.write)
            state.value = state.value.copy(rows = state.value.rows.map { if (it.url == url) it.copy(allowedHere = true) else it })
        }

    /** Starts the draft again from the relays this device is allowed to use. */
    fun fillFromAllowedRelays() {
        val entries =
            core.relayDirectory.grants.values
                .filter { it.isApproved }
                .map { RelayListEntry(it.url, read = it.read, write = it.write) }
        state.value = state.value.copy(rows = entries.toRows(), edited = true)
    }

    /** Throws the draft away and shows what is actually published again. */
    fun discardChanges() = load()

    /**
     * Signs and publishes the draft as a kind 10002.
     *
     * The only network write on this screen, and the only moment any of these
     * relay names becomes public.
     */
    fun publish() =
        scope.launch {
            val me = core.accounts.account.value
            val signer = core.accounts.signer
            if (me == null || signer == null || !signer.canSign) {
                report(UserMessage.Error("This account holds no key here, so it cannot publish a relay list."))
                return@launch
            }

            val entries = state.value.rows.map { RelayListEntry(it.url, read = it.read, write = it.write) }
            if (entries.isEmpty()) {
                report(UserMessage.Error("Add at least one relay before publishing."))
                return@launch
            }

            when (val result = core.relayListRepo.publishOwn(signer, me.pubKey, entries)) {
                is PublishResult.Success -> {
                    report(UserMessage.Published(result.report))
                    state.value =
                        state.value.copy(
                            publishedAt = result.report.event.createdAt,
                            isSuggestion = false,
                            edited = false,
                        )
                    onPublished()
                }
                is PublishResult.Failure ->
                    report(
                        UserMessage.Error(
                            "The relay list could not be published: ${result.error.describe()}",
                        ),
                    )
            }
        }

    /** Someone else's advertised list, from the cache routing already fills. Never fetched here. */
    fun advertisedBy(pubKey: PubKey): List<RelayListEntry> = core.relayLists[pubKey]?.entries.orEmpty()

    private fun List<RelayListEntry>.toRows(): List<RelayListRow> =
        map { RelayListRow(it.url, it.read, it.write, core.relayDirectory.isApproved(it.url)) }
            .sortedBy { it.url.display() }

    private fun PublishError.describe(): String =
        when (this) {
            PublishError.NotSignedIn -> "you are not signed in"
            PublishError.WatchOnlyAccount -> "this account holds no key here"
            PublishError.NoApprovedWriteRelay ->
                "no relay is allowed to send your posts, so there is nowhere to publish it. " +
                    "Allow one for posting in Relays first."
            is PublishError.Rejected -> "every relay refused it"
        }
}
