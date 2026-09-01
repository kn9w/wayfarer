package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.Article
import app.wayfarer.core.model.ArticleDraft
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
import app.wayfarer.core.relay.RelayInfoService
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
    /**
     * Resolves the "ask a signer app who the user is" hook, or null when no signer
     * is installed — which is also what hides the option in the UI.
     *
     * A provider rather than the hook itself: the activity supplies a capturing
     * lambda that changes identity on every recomposition, and holding one
     * instance here would tie the controller's lifetime to it.
     */
    private val externalSignerLogin: () -> (suspend () -> ExternalSignerIdentity?)? = { null },
    /**
     * Asks Android to confirm the device owner — biometric or device credential —
     * before the secret key is put on screen. Same provider shape, and for the
     * same reason, as [externalSignerLogin].
     */
    private val deviceAuth: () -> (suspend () -> DeviceAuthOutcome)? = { null },
    /** Reads a QR code with the camera, or null when no scanner is wired up. */
    private val qrScan: () -> (suspend () -> String?)? = { null },
) {
    private val screenState = MutableStateFlow<Screen>(Screen.Home)
    private val onboardingState = MutableStateFlow<OnboardingStep?>(null)
    private val busyState = MutableStateFlow(false)
    private val messageState = MutableStateFlow<UserMessage?>(null)
    private val feedState = MutableStateFlow(FeedState())
    private val viewedProfileState = MutableStateFlow<ViewedProfile?>(null)
    private val articleState = MutableStateFlow<List<Article>>(emptyList())
    private val relayInfoPromptState = MutableStateFlow<RelayUrl?>(null)
    private val revealedKeyState = MutableStateFlow<String?>(null)
    private val relayListPromptState = MutableStateFlow(false)

    /**
     * Whether this user has been through the introduction before.
     *
     * Kept as a plain field because the UI asks at composition time: it decides
     * whether the sign-in screen can be backed out of, which is true for a guest
     * returning to it and false on a first launch, where there is nothing behind it.
     */
    private var introduced = false

    /**
     * Whether the relay client has been brought up.
     *
     * Reading is not something only signed-in users do — a guest session reads
     * exactly as much — so starting the transport belongs with the first read
     * rather than with sign-in. It still opens no socket on its own: the gate
     * decides that, and with nothing approved there is nothing to dial.
     */
    private var transportStarted = false

    val screen: StateFlow<Screen> = screenState.asStateFlow()

    /**
     * The onboarding surface, or null once it is done with.
     *
     * Non-null means onboarding owns the whole window — no tab bar, no other
     * destination. That is not cosmetic: the key backup screen lives in here, and
     * a stray tap on a navigation bar drawn around it used to lose the key for
     * good.
     */
    val onboarding: StateFlow<OnboardingStep?> = onboardingState.asStateFlow()

    val busy: StateFlow<Boolean> = busyState.asStateFlow()
    val message: StateFlow<UserMessage?> = messageState.asStateFlow()
    val feed: StateFlow<FeedState> = feedState.asStateFlow()
    val viewedProfile: StateFlow<ViewedProfile?> = viewedProfileState.asStateFlow()
    val articles: StateFlow<List<Article>> = articleState.asStateFlow()

    /** Set while the user is being asked to confirm a NIP-11 fetch. */
    val relayInfoPrompt: StateFlow<RelayUrl?> = relayInfoPromptState.asStateFlow()

    /** The account's nsec while it is on screen in settings, and only then. */
    val revealedSecretKey: StateFlow<String?> = revealedKeyState.asStateFlow()

    /**
     * True when the account can post but has never advertised a relay list, so
     * other people's clients have no way to find its posts.
     *
     * A prompt rather than an automatic publish: a kind 10002 is a signed public
     * event naming relays, and this app does not put those on the network on the
     * user's behalf.
     */
    val shouldOfferRelayListPublish: StateFlow<Boolean> = relayListPromptState.asStateFlow()

    val relayInfo: StateFlow<Map<RelayUrl, RelayInfoService.Entry>> get() = core.relayInfo.entries

    /** True when a NIP-55 signer app is installed on this device. */
    val externalSignerAvailable: Boolean get() = externalSignerLogin() != null

    /** True when this device can scan a QR code, which hides the button when it cannot. */
    val qrScanAvailable: Boolean get() = qrScan() != null

    val account: StateFlow<Account?> get() = core.accounts.account

    /** The relays this build ships with. Shown before they are ever queried. */
    val suggestedRelays: List<RelayUrl> get() = core.suggestedRelays

    val relays = RelayController(core, scope, { messageState.value = it }, ::onRelayPermissionsChanged)

    /**
     * The account's public NIP-65 list, which is not the same thing as [relays]
     * and is kept in its own view model so the two cannot drift into one screen.
     */
    val relayList = RelayListController(core, scope, { messageState.value = it }, ::recomputeRelayListPrompt)

    val connectedRelays: StateFlow<Set<RelayUrl>> get() = core.transport.connected

    init {
        // Restoring reads the keystore and then talks to relays, either of which
        // can fail. Without this the failure disappears into the SupervisorJob and
        // the user is left on a blank signed-out screen with no explanation.
        run {
            val restored = core.accounts.restore()
            when {
                restored != null -> onSignedIn(restored)
                // A user who went through the introduction and chose not to make an
                // account is not a new user. Sending them back to the first screen on
                // every launch would read as the app refusing to let them in.
                core.onboarding.isComplete() -> {
                    introduced = true
                    loadFeed()
                }
                else -> onboardingState.value = OnboardingStep.Start
            }
        }
    }

    // ---- navigation -------------------------------------------------------

    fun go(destination: Screen) {
        // Leaving settings takes the key off screen with it: it is shown for as
        // long as it is being read, and not one navigation longer.
        if (destination !is Screen.Settings) revealedKeyState.value = null
        screenState.value = destination
    }

    fun dismissMessage() {
        messageState.value = null
    }

    // ---- onboarding -------------------------------------------------------

    /** "New to nostr?" — the introduction, before any account or relay exists. */
    fun beginIntroduction() {
        onboardingState.value = OnboardingStep.Learn(0)
    }

    fun introductionNext() {
        val current = onboardingState.value as? OnboardingStep.Learn ?: return
        val next = current.page + 1
        onboardingState.value =
            if (next < Introduction.pages.size) OnboardingStep.Learn(next) else OnboardingStep.AccountChoice
    }

    fun introductionBack() {
        val current = onboardingState.value as? OnboardingStep.Learn ?: return
        onboardingState.value = if (current.page == 0) OnboardingStep.Start else OnboardingStep.Learn(current.page - 1)
    }

    /** Opens the sign-in surface: from the introduction, or from a guest session. */
    fun goToSignIn() {
        onboardingState.value = OnboardingStep.Start
    }

    /** True when there is an app behind the onboarding surface to go back to. */
    val canLeaveOnboarding: Boolean get() = introduced

    /** Backs out of the sign-in screen, for a guest who opened it and changed their mind. */
    fun leaveOnboarding() {
        if (introduced) onboardingState.value = null
    }

    /**
     * Carries on with no account at all.
     *
     * Reading nostr needs no key, so this is a real choice rather than a delay:
     * everything except publishing works, and the app says which is which when a
     * key turns out to be needed.
     */
    fun continueWithoutAccount() {
        onboardingState.value = OnboardingStep.EntryPoint
    }

    /** Leaves the key backup screen. Only reachable from the backup screen itself. */
    fun finishBackup() {
        if (onboardingState.value is OnboardingStep.Backup) onboardingState.value = OnboardingStep.EntryPoint
    }

    /** Ends onboarding without choosing a starting point. */
    fun skipEntryPoint() =
        run {
            completeOnboarding()
        }

    /**
     * Resolves what the user typed — or scanned — as a starting point.
     *
     * Two shapes are accepted, and they lead to different questions: a relay can
     * simply be approved and read, while a person has to be *found*, which means
     * asking somebody. Whether that somebody is a relay the pointer itself named
     * or a relay this app picked is the thing the next screen is about.
     */
    fun submitEntryPoint(input: String) =
        run {
            handleEntryPoint(input)
        }

    fun scanEntryPoint() =
        run {
            val scan = qrScan() ?: return@run
            // Null is a cancelled scan, which is not an error worth a banner.
            val scanned = scan() ?: return@run
            handleEntryPoint(scanned)
        }

    /** Approves the relays the current step names, then does what it was for. */
    fun approveProposedRelays() =
        run {
            val step = onboardingState.value as? OnboardingStep.ApproveRelays ?: return@run
            for (url in step.relays) core.relayDirectory.approve(url, read = true, write = false)
            continueWith(step.purpose)
        }

    /** Uses a relay the user names instead of the ones the app proposed. */
    fun useRelayInstead(raw: String) =
        run {
            val step = onboardingState.value as? OnboardingStep.ApproveRelays ?: return@run
            val url = core.addRelay(raw, read = true, write = false)
            if (url == null) {
                messageState.value = UserMessage.Error(notARelay(raw))
                return@run
            }
            continueWith(step.purpose)
        }

    /** Offers the app's own relays as a starting point, saying so plainly. */
    fun browseSuggestedRelays() {
        onboardingState.value = proposeDefaults(RelayPurpose.Browse)
    }

    private suspend fun handleEntryPoint(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        core.bech32.decodeProfileRef(trimmed)?.let { ref ->
            val hints = ref.relayHints.mapNotNull(core.normalizer::normalize).distinct()
            val purpose = RelayPurpose.FindPerson(ref.pubKey, core.bech32.encodeNpub(ref.pubKey))
            onboardingState.value =
                if (hints.isEmpty()) {
                    // A bare npub says who, never where. Somebody has to be asked,
                    // and the user gets to know who that is before it happens.
                    proposeDefaults(purpose)
                } else {
                    OnboardingStep.ApproveRelays(purpose, hints, areAppDefaults = false)
                }
            return
        }

        if (looksLikeRelay(trimmed)) {
            val url = core.addRelay(trimmed, read = true, write = false)
            if (url != null) {
                completeOnboarding()
                loadFeed()
                return
            }
        }
        messageState.value = UserMessage.Error(notARelay(trimmed))
    }

    /**
     * Tried only after a NIP-19 decode has failed, because a relay URL normalizer
     * will happily turn `npub1…` into `wss://npub1…/`.
     *
     * The bech32 prefixes are matched with their `1` separator, not by first
     * letter: `nos.lol` is one of the best-known relays there is, and dropping
     * everything beginning with an n would reject it.
     */
    private fun looksLikeRelay(input: String): Boolean {
        val lower = input.lowercase()
        if (lower.startsWith("wss://") || lower.startsWith("ws://")) return true
        if (lower.startsWith("nostr:") || BECH32_PREFIXES.any { lower.startsWith(it) }) return false
        return "." in lower && " " !in lower
    }

    private fun proposeDefaults(purpose: RelayPurpose) =
        OnboardingStep.ApproveRelays(purpose, core.suggestedRelays, areAppDefaults = true)

    private suspend fun continueWith(purpose: RelayPurpose) {
        when (purpose) {
            is RelayPurpose.FindPerson -> {
                completeOnboarding()
                openProfileNow(purpose.pubKey)
            }
            is RelayPurpose.FindAccount -> {
                core.accounts.account.value?.let { onSignedIn(it) }
                completeOnboarding()
            }
            RelayPurpose.Browse -> {
                completeOnboarding()
                loadFeed()
            }
        }
    }

    private suspend fun completeOnboarding() {
        introduced = true
        core.onboarding.markComplete()
        onboardingState.value = null
        screenState.value = Screen.Home
        recomputeRelayListPrompt()
    }

    private companion object {
        /** NIP-19 entity prefixes, with their bech32 separator. */
        val BECH32_PREFIXES = listOf("npub1", "nsec1", "nprofile1", "note1", "nevent1", "naddr1", "nrelay1")
    }

    private fun notARelay(raw: String) =
        "\"$raw\" is not a relay address, an npub or an nprofile. A relay looks like wss://relay.example.com."

    // ---- account ----------------------------------------------------------

    /**
     * Generates a key and shows it.
     *
     * The backup step is set *before* the sign-in work, so the key is on screen
     * from the first frame and cannot be raced by anything the sign-in does.
     */
    fun createAccount() =
        run {
            val (account, nsec) = core.accounts.createAccount()
            onboardingState.value = OnboardingStep.Backup(nsec)
            onSignedIn(account)
        }

    fun login(input: String) =
        run {
            when (val result = core.accounts.login(input)) {
                is LoginResult.Success -> afterSignIn(result.account)
                LoginResult.NotAKey ->
                    messageState.value = UserMessage.Error("That is not an npub or an nsec. Paste either one, or a raw hex key.")
            }
        }

    /**
     * Signs in through a NIP-55 signer app. The app learns the user's pubkey and
     * the signer's package name, and never sees a key.
     */
    fun loginWithExternalSigner() =
        run {
            val login = externalSignerLogin() ?: return@run
            val identity = login()
            if (identity == null) {
                messageState.value = UserMessage.Error("The signer app did not return a public key.")
                return@run
            }
            afterSignIn(core.accounts.loginWithExternalSigner(identity.pubKey, identity.packageName))
        }

    /**
     * With nothing approved, finding out who this account *is* — its profile,
     * follows and relay list — means querying relays the user has not chosen. So
     * that is asked for rather than done.
     */
    private suspend fun afterSignIn(account: Account) {
        if (core.relayDirectory.grants.isEmpty()) {
            onboardingState.value = proposeDefaults(RelayPurpose.FindAccount(account.pubKey))
            return
        }
        onSignedIn(account)
        completeOnboarding()
    }

    fun logout() =
        run {
            core.accounts.logout()
            core.contacts.clear()
            feedState.value = FeedState()
            revealedKeyState.value = null
            relayListPromptState.value = false
            screenState.value = Screen.Home
            onboardingState.value = OnboardingStep.Start
        }

    /**
     * Puts the account's nsec on screen, after Android has confirmed the device
     * owner.
     *
     * This is the other half of key safety: the backup screen is shown once, and
     * a key that could never be seen again would be one bad moment away from
     * being lost. [Account.hasLocalKey] gates it — there is nothing to show for a
     * watch-only account or an external signer, which holds the key elsewhere.
     */
    fun revealSecretKey() =
        run {
            val account = core.accounts.account.value
            if (account == null || !account.hasLocalKey) {
                messageState.value = UserMessage.Error("This account holds no key on this device, so there is nothing to show.")
                return@run
            }

            when (deviceAuth()?.invoke() ?: DeviceAuthOutcome.UNAVAILABLE) {
                DeviceAuthOutcome.CONFIRMED -> Unit
                DeviceAuthOutcome.REJECTED -> {
                    messageState.value = UserMessage.Error("Not confirmed, so the key stays hidden.")
                    return@run
                }
                // No screen lock, or no way to ask: refusing outright would lock the
                // user out of their own key, so it is shown and the gap is named.
                DeviceAuthOutcome.UNAVAILABLE ->
                    messageState.value =
                        UserMessage.Info(
                            "Your phone did not ask you to confirm — it has no screen lock set, or could not " +
                                "show one. Anyone holding this phone can reach this key.",
                        )
            }

            val nsec = core.accounts.revealSecretKey()
            if (nsec == null) {
                messageState.value = UserMessage.Error("The key could not be read back from this device's keystore.")
            } else {
                revealedKeyState.value = nsec
            }
        }

    fun hideSecretKey() {
        revealedKeyState.value = null
    }

    /**
     * After sign-in: bring the transport up, learn our own relay list and
     * follows, then load the feed. Each step is allowed to find nothing — a
     * brand-new account has no relay list, and no relay is approved yet.
     *
     * Nothing here touches navigation. Where the user lands is onboarding's
     * decision, and a network call finishing must never move them.
     */
    private suspend fun onSignedIn(account: Account) {
        ensureTransportStarted()

        core.relayListRepo.refresh(account.pubKey)?.let { core.relayListRepo.offerToDirectory(it, isOwnAccount = true) }
        core.profiles.load(account.pubKey)
        core.contacts.load(account.pubKey)

        loadFeed()
        recomputeRelayListPrompt()
    }

    // ---- feed -------------------------------------------------------------

    fun refreshFeed() =
        run {
            loadFeed()
        }

    /**
     * Loads whatever this user can currently be shown.
     *
     * Two different claims, kept apart on purpose. With follows, the feed is
     * outbox-routed: each author read from where that author says they publish.
     * With none — a fresh account, or someone looking around without one — there
     * is no author to route by, so it falls back to reading the relays the user
     * approved and [FeedState.browsingRelays] says so, rather than dressing one
     * up as the other.
     */
    private suspend fun loadFeed() {
        ensureTransportStarted()

        val me = core.accounts.account.value
        val follows = core.contacts.follows.value
        val routed = follows.isNotEmpty()

        val result =
            if (routed) {
                core.feed.load(follows + setOfNotNull(me?.pubKey))
            } else {
                core.feed.loadFromRelays(approvedReadRelays())
            }

        core.profiles.loadAll(result.notes.mapTo(mutableSetOf()) { it.author } + setOfNotNull(me?.pubKey))

        articleState.value = core.articles.all.value.values.sortedByDescending { it.publishedAt }

        feedState.value =
            FeedState(
                notes = result.notes,
                unreachableAuthors = result.unreachableAuthors,
                guessedAuthors = result.guessedAuthors,
                relaysQueried = result.relaysQueried.size,
                browsingRelays = if (routed) emptySet() else result.relaysQueried,
                profiles = core.profiles.profiles.value,
                loaded = true,
            )
    }

    private fun ensureTransportStarted() {
        if (transportStarted) return
        transportStarted = true
        core.transport.start()
    }

    private fun approvedReadRelays(): Set<RelayUrl> =
        core.relayDirectory.grants.values
            .filter { it.read }
            .mapTo(mutableSetOf()) { it.url }

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
                loadFeed()
            }
            is PublishResult.Failure -> messageState.value = result.error.toMessage()
        }
    }

    // ---- relay permissions ------------------------------------------------

    /**
     * Approving a relay is not a note in a settings file: it is the one thing
     * standing between the user and the posts they are waiting for, so the feed
     * reloads immediately rather than leaving them to work out that they must go
     * and press Refresh.
     *
     * Nothing is published. The permission list is local to this app — it is not
     * a NIP-65 relay list and no event is written when it changes; advertising
     * relays to the network stays a separate, explicit action.
     */
    private fun onRelayPermissionsChanged() {
        run {
            recomputeRelayListPrompt()
            loadFeed()
        }
    }

    private fun recomputeRelayListPrompt() {
        val me = core.accounts.account.value
        val canSign = me != null && me.canSign
        val hasWriteRelay = core.relayDirectory.grants.values.any { it.write }
        relayListPromptState.value = canSign && hasWriteRelay && core.relayLists[me!!.pubKey] == null
    }

    // ---- profiles ---------------------------------------------------------

    /** Opens the NIP-65 editor and fetches whatever is currently published. */
    fun openRelayList() {
        screenState.value = Screen.RelayList
        relayList.load()
    }

    /** What [pubKey] advertises, from the cache outbox routing already fills. */
    fun advertisedRelaysFor(pubKey: PubKey) = relayList.advertisedBy(pubKey)

    fun openProfile(pubKey: PubKey) =
        run {
            openProfileNow(pubKey)
        }

    private suspend fun openProfileNow(pubKey: PubKey) {
        ensureTransportStarted()
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
        val ref = core.bech32.decodeProfileRef(input)
        if (ref == null) {
            messageState.value = UserMessage.Error("That is not an npub or a hex pubkey.")
            return
        }
        // An nprofile says where to find this person. Those relays are offered for
        // approval rather than used: a hint is somebody else's suggestion.
        val hints = ref.relayHints.mapNotNull(core.normalizer::normalize)
        if (hints.isNotEmpty()) {
            run { core.relayDirectory.noteHint(hints, core.bech32.encodeNpub(ref.pubKey)) }
        }
        openProfile(ref.pubKey)
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
                    openProfileNow(me.pubKey)
                }
                is PublishResult.Failure -> messageState.value = result.error.toMessage()
            }
        }

    fun profileFor(pubKey: PubKey): Profile? = core.profiles[pubKey]

    fun npubFor(pubKey: PubKey): String = core.bech32.encodeNpub(pubKey)

    // ---- long-form (NIP-23) -----------------------------------------------

    fun articleDraft(address: String?): ArticleDraft {
        val existing = address?.let { core.articles[it] }
        return existing?.let(ArticleDraft::from) ?: ArticleDraft("", "", "", "")
    }

    fun publishArticle(draft: ArticleDraft) =
        run {
            val me = core.accounts.account.value ?: return@run
            val signer = core.accounts.signer ?: return@run
            when (val result = core.articles.publish(signer, me.pubKey, draft)) {
                is PublishResult.Success -> {
                    messageState.value = UserMessage.Published(result.report)
                    articleState.value = core.articles.all.value.values.sortedByDescending { it.publishedAt }
                    screenState.value = Screen.Home
                }
                is PublishResult.Failure -> messageState.value = result.error.toMessage()
            }
        }

    // ---- relay information (NIP-11) ---------------------------------------

    /**
     * Reads a relay's NIP-11 document.
     *
     * For a relay with no grant this is a connection the user has not otherwise
     * authorised, so it asks first; [confirmRelayInfoFetch] is what actually
     * sends anything. For an approved relay the connection is already sanctioned
     * and it goes straight through.
     */
    fun requestRelayInfo(url: RelayUrl) {
        if (core.relayDirectory.isApproved(url)) {
            fetchRelayInfo(url)
        } else {
            relayInfoPromptState.value = url
        }
    }

    fun confirmRelayInfoFetch() {
        val url = relayInfoPromptState.value ?: return
        relayInfoPromptState.value = null
        fetchRelayInfo(url)
    }

    fun dismissRelayInfoPrompt() {
        relayInfoPromptState.value = null
    }

    private fun fetchRelayInfo(url: RelayUrl) {
        scope.launch { core.relayInfo.fetchOnUserRequest(url) }
    }

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
                UserMessage.Error("No relay is approved for posting. Approve one for posting in Relays first.")
            is PublishError.Rejected -> UserMessage.Published(report)
        }
}

sealed interface Screen {
    data object Home : Screen

    data object Relays : Screen

    data object Compose : Screen

    data object EditProfile : Screen

    data object Articles : Screen

    /** Account, key backup and the introduction, in one place. */
    data object Settings : Screen

    /** The account's own NIP-65 list: where other people should look for it. */
    data object RelayList : Screen

    /** Null address is a new article; otherwise an edit that keeps the d tag. */
    data class EditArticle(
        val address: String?,
    ) : Screen

    data class ReadArticle(
        val address: String,
    ) : Screen

    data class Profile(
        val pubKey: PubKey,
    ) : Screen
}

/**
 * Onboarding, as a sequence rather than a set of destinations.
 *
 * These are deliberately *not* [Screen]s. A screen is somewhere the navigation
 * bar can take you; every step here owns the whole window, which is what keeps
 * the one-time key backup from being one mis-tap away from gone.
 */
sealed interface OnboardingStep {
    /** The launch screen: what this is, and the ways in. */
    data object Start : OnboardingStep

    /** The introduction. One idea per page, in the user's own words. */
    data class Learn(
        val page: Int,
    ) : OnboardingStep

    /** Make an account, or carry on without one. Equal weight, both real. */
    data object AccountChoice : OnboardingStep

    /** The new key, shown once, with no way to leave except acknowledging it. */
    data class Backup(
        val nsec: String,
    ) : OnboardingStep

    /** Where to start looking: a relay, or somebody's npub. */
    data object EntryPoint : OnboardingStep

    /**
     * Consent for the relays the next step would talk to.
     *
     * The one place this app proposes relays of its own, and it says so:
     * [areAppDefaults] is the difference between "the link you pasted names
     * these" and "we have nowhere else to look".
     */
    data class ApproveRelays(
        val purpose: RelayPurpose,
        val relays: List<RelayUrl>,
        val areAppDefaults: Boolean,
    ) : OnboardingStep
}

/** What a set of relays is about to be queried *for*. */
sealed interface RelayPurpose {
    data class FindPerson(
        val pubKey: PubKey,
        val npub: String,
    ) : RelayPurpose

    /** The signed-in account's own profile, follows and relay list. */
    data class FindAccount(
        val pubKey: PubKey,
    ) : RelayPurpose

    data object Browse : RelayPurpose
}

/** What Android said when asked to confirm the device owner. */
enum class DeviceAuthOutcome {
    CONFIRMED,
    REJECTED,

    /** No screen lock is set, or no launcher is attached to ask with. */
    UNAVAILABLE,
}

/** The introduction's copy. Kept here so the wording is testable, not buried in a composable. */
object Introduction {
    data class Page(
        val title: String,
        val body: List<String>,
    )

    val pages =
        listOf(
            Page(
                "There is no account to sign up for",
                listOf(
                    "Nostr has no company behind it and no sign-up form. Your account is a pair of numbers this " +
                        "phone generates, and nobody registers it anywhere.",
                    "The public one is your name — it starts with npub, and you can hand it to anyone.",
                    "The secret one is your password — it starts with nsec, and it is the account. Anyone holding " +
                        "it is you. Nobody can reset it, and nobody can recover it for you, this app included.",
                ),
            ),
            Page(
                "Your posts live on relays",
                listOf(
                    "A relay is just a server that keeps posts and hands them out. There are hundreds, run by " +
                        "different people, and none of them is \"the\" nostr server.",
                    "You pick which ones this app talks to. Reading from a relay asks it for posts; posting to a " +
                        "relay puts yours there for other people to read.",
                    "If your posts are on a relay nobody you know reads, nobody sees them. That is the trade for " +
                        "there being no single company in the middle.",
                ),
            ),
            Page(
                "Nothing is contacted until you say so",
                listOf(
                    "This app opens no connection to a relay you have not approved. Not on first launch, not in " +
                        "the background, not to check for updates.",
                    "That also means it cannot find anything on its own. When it needs to ask a relay you have " +
                        "not chosen, it will show you which one and wait.",
                    "You can change any of it later, and nothing you approve here is published to anyone: the list " +
                        "of relays this app may use is kept on this phone.",
                ),
            ),
        )
}

data class FeedState(
    val notes: List<Note> = emptyList(),
    val unreachableAuthors: Set<PubKey> = emptySet(),
    val guessedAuthors: Set<PubKey> = emptySet(),
    val relaysQueried: Int = 0,
    /**
     * Non-empty when these notes are simply what those relays are carrying,
     * rather than an outbox-routed feed of anyone's follows.
     */
    val browsingRelays: Set<RelayUrl> = emptySet(),
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

/** What a NIP-55 signer app told us about the user. */
data class ExternalSignerIdentity(
    val pubKey: PubKey,
    val packageName: String,
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
