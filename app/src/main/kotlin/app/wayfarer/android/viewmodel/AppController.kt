package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.ProfileDraft
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.Mentions
import app.wayfarer.core.repo.Account
import app.wayfarer.core.repo.LoginResult
import app.wayfarer.core.repo.PublishError
import app.wayfarer.core.repo.PublishReport
import app.wayfarer.core.repo.PublishResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The UI's façade over [Wayfarer].
 *
 * Holds navigation and transient screen state, and does nothing else: every
 * decision about relays, routing, signing or event shape lives in `core`. If a
 * method here grows a rule of its own, it belongs one layer down.
 */
class AppController(
    private val core: Wayfarer,
    private val scope: CoroutineScope,
) {
    private val screenState = MutableStateFlow<Screen>(Screen.Home)
    private val busyState = MutableStateFlow(false)
    private val messageState = MutableStateFlow<UserMessage?>(null)
    private val feedState = MutableStateFlow(FeedState())
    private val viewedProfileState = MutableStateFlow<ViewedProfile?>(null)

    val screen: StateFlow<Screen> = screenState.asStateFlow()
    val busy: StateFlow<Boolean> = busyState.asStateFlow()
    val message: StateFlow<UserMessage?> = messageState.asStateFlow()
    val feed: StateFlow<FeedState> = feedState.asStateFlow()
    val viewedProfile: StateFlow<ViewedProfile?> = viewedProfileState.asStateFlow()

    val account: StateFlow<Account?> get() = core.accounts.account
    val relays = RelayController(core, scope) { messageState.value = it }

    val connectedRelays: StateFlow<Set<RelayUrl>> get() = core.transport.connected

    init {
        scope.launch {
            core.accounts.restore()?.let { onSignedIn(it, fresh = false) }
        }
    }

    // ---- navigation -------------------------------------------------------

    fun go(destination: Screen) {
        screenState.value = destination
    }

    fun dismissMessage() {
        messageState.value = null
    }

    // ---- account ----------------------------------------------------------

    fun createAccount() =
        run {
            val (account, nsec) = core.accounts.createAccount()
            onSignedIn(account, fresh = true)
            screenState.value = Screen.Backup(nsec)
        }

    fun login(input: String) =
        run {
            when (val result = core.accounts.login(input)) {
                is LoginResult.Success -> {
                    onSignedIn(result.account, fresh = true)
                    screenState.value = if (core.relayDirectory.grants.isEmpty()) Screen.Relays else Screen.Home
                }
                LoginResult.NotAKey ->
                    messageState.value = UserMessage.Error("That is not an npub or an nsec. Paste either one, or a raw hex key.")
            }
        }

    fun logout() =
        run {
            core.accounts.logout()
            core.contacts.clear()
            feedState.value = FeedState()
            screenState.value = Screen.Home
        }

    suspend fun revealSecretKey(): String? = core.accounts.revealSecretKey()

    /**
     * After sign-in: bring the transport up, learn our own relay list and
     * follows, then load the feed. Each step is allowed to find nothing — a
     * brand-new account has no relay list, and no relay is approved yet.
     */
    private suspend fun onSignedIn(
        account: Account,
        fresh: Boolean,
    ) {
        core.transport.start()

        core.relayListRepo.refresh(account.pubKey)?.let { core.relayListRepo.offerToDirectory(it, isOwnAccount = true) }
        core.profiles.load(account.pubKey)
        core.contacts.load(account.pubKey)

        if (fresh && core.relayDirectory.grants.isEmpty()) {
            screenState.value = Screen.Relays
        }
        refreshFeed()
    }

    // ---- feed -------------------------------------------------------------

    fun refreshFeed() =
        run {
            val me = core.accounts.account.value ?: return@run
            val authors = core.contacts.follows.value + me.pubKey

            val result = core.feed.load(authors)
            for (author in authors) {
                if (core.profiles[author] == null) core.profiles.load(author)
            }

            feedState.value =
                FeedState(
                    notes = result.notes,
                    unreachableAuthors = result.unreachableAuthors,
                    guessedAuthors = result.guessedAuthors,
                    relaysQueried = result.relaysQueried.size,
                    profiles = core.profiles.profiles.value,
                    loaded = true,
                )
        }

    fun post(
        content: String,
        replyTo: Note? = null,
    ) = run {
        val me = core.accounts.account.value ?: return@run
        val signer = core.accounts.signer ?: return@run
        // Mentions drive the inbox half of outbox routing, so they are parsed
        // out of the note text rather than needing a separate picker.
        val mentions = Mentions.extract(content, core.bech32)

        when (val result = core.feed.post(signer, me.pubKey, content, mentions, replyTo)) {
            is PublishResult.Success -> {
                messageState.value = UserMessage.Published(result.report)
                screenState.value = Screen.Home
                refreshFeed()
            }
            is PublishResult.Failure -> messageState.value = result.error.toMessage()
        }
    }

    // ---- profiles ---------------------------------------------------------

    fun openProfile(pubKey: PubKey) =
        run {
            screenState.value = Screen.Profile(pubKey)
            viewedProfileState.value = ViewedProfile(pubKey, core.profiles[pubKey], emptyList(), loading = true)

            core.profiles.load(pubKey)
            val notes = core.feed.load(setOf(pubKey))

            viewedProfileState.value =
                ViewedProfile(
                    pubKey = pubKey,
                    profile = core.profiles[pubKey],
                    notes = notes.notes,
                    loading = false,
                    unreachable = pubKey in notes.unreachableAuthors,
                    npub = core.bech32.encodeNpub(pubKey),
                )
        }

    /** Resolves an npub / nprofile / hex key typed into the search field. */
    fun openProfileByKey(input: String) {
        val pubKey = core.bech32.decodePubKey(input)
        if (pubKey == null) {
            messageState.value = UserMessage.Error("That is not an npub or a hex pubkey.")
        } else {
            openProfile(pubKey)
        }
    }

    fun ownProfileDraft(): ProfileDraft {
        val me = core.accounts.account.value ?: return ProfileDraft("", "", "", "", "", "", "", "")
        return ProfileDraft.from(core.profiles[me.pubKey] ?: Profile.empty(me.pubKey))
    }

    fun saveProfile(draft: ProfileDraft) =
        run {
            val me = core.accounts.account.value ?: return@run
            val signer = core.accounts.signer ?: return@run
            when (val result = core.profiles.publish(signer, me.pubKey, draft)) {
                is PublishResult.Success -> {
                    messageState.value = UserMessage.Published(result.report)
                    screenState.value = Screen.Profile(me.pubKey)
                    openProfile(me.pubKey)
                }
                is PublishResult.Failure -> messageState.value = result.error.toMessage()
            }
        }

    fun profileFor(pubKey: PubKey): Profile? = core.profiles[pubKey]

    fun npubFor(pubKey: PubKey): String = core.bech32.encodeNpub(pubKey)

    // ---- plumbing ---------------------------------------------------------

    /** Runs [block] with the busy flag set, reporting anything it throws. */
    private fun run(block: suspend () -> Unit) {
        scope.launch {
            busyState.value = true
            try {
                block()
            } catch (failure: Throwable) {
                messageState.value = UserMessage.Error(failure.message ?: failure::class.simpleName ?: "Something failed")
            } finally {
                busyState.value = false
            }
        }
    }

    private fun PublishError.toMessage(): UserMessage =
        when (this) {
            PublishError.NotSignedIn -> UserMessage.Error("Sign in first.")
            PublishError.WatchOnlyAccount -> UserMessage.Error("This account was added with an npub, so it cannot publish.")
            PublishError.NoApprovedWriteRelay ->
                UserMessage.Error("No relay is approved for writing. Approve one in Relays first.")
            is PublishError.Rejected -> UserMessage.Published(report)
        }
}

sealed interface Screen {
    data object Home : Screen

    data object Relays : Screen

    data object Compose : Screen

    data object EditProfile : Screen

    data class Profile(
        val pubKey: PubKey,
    ) : Screen

    data class Backup(
        val nsec: String,
    ) : Screen
}

data class FeedState(
    val notes: List<Note> = emptyList(),
    val unreachableAuthors: Set<PubKey> = emptySet(),
    val guessedAuthors: Set<PubKey> = emptySet(),
    val relaysQueried: Int = 0,
    val profiles: Map<PubKey, Profile> = emptyMap(),
    val loaded: Boolean = false,
)

data class ViewedProfile(
    val pubKey: PubKey,
    val profile: Profile?,
    val notes: List<Note>,
    val loading: Boolean,
    val unreachable: Boolean = false,
    val npub: String = "",
)

sealed interface UserMessage {
    data class Error(
        val text: String,
    ) : UserMessage

    data class Info(
        val text: String,
    ) : UserMessage

    /** A publish result, shown per relay — the interesting part of outbox routing. */
    data class Published(
        val report: PublishReport,
    ) : UserMessage
}
