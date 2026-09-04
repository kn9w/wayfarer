package app.wayfarer.android.viewmodel

import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.Article
import app.wayfarer.core.model.ArticleDraft
import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.MediaReason
import app.wayfarer.core.model.MediaSource
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.PaymentTarget
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.ProfileDraft
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.Mentions
import app.wayfarer.core.relay.RelayInfoService
import app.wayfarer.core.repo.Account
import app.wayfarer.core.repo.AccountSummary
import app.wayfarer.core.repo.FollowSource
import app.wayfarer.core.repo.HeaderStyle
import app.wayfarer.core.repo.LoginResult
import app.wayfarer.core.repo.PublishError
import app.wayfarer.core.repo.PublishReport
import app.wayfarer.core.repo.PublishResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /**
     * What the installed signer app calls itself, or null when none is
     * installed. Named on the sign-in screen: "Log in with Amber" is something a
     * person recognises, where "use a signer app" is a category they may not know
     * they are in.
     */
    private val externalSignerName: () -> String? = { null },
    /**
     * Throws away every cached picture, or does nothing where no loader is
     * wired up.
     *
     * A provider rather than the hook itself, for the reason the others are —
     * and used in exactly one place: logging out, where the pictures an account
     * drew are as much its trace as the lists it granted.
     */
    private val clearImageCache: () -> (suspend () -> Unit)? = { null },
) {
    private val screenState = MutableStateFlow<Screen>(Screen.Home)

    /**
     * Where back goes, most recent last.
     *
     * Navigation is a single state value rather than a library, which left the
     * system back button falling through to the activity — it closed the app,
     * from any screen, including the one-time key backup. This is the history
     * that was missing. Tab switches clear it rather than pushing, so moving
     * between tabs cannot build a stack the user has to unwind.
     */
    private val backStack = ArrayDeque<Screen>()

    private val canGoBackState = MutableStateFlow(false)
    private val onboardingState = MutableStateFlow<OnboardingStep?>(null)
    private val busyState = MutableStateFlow(false)

    /**
     * A refresh the user asked for, as distinct from [busyState].
     *
     * Loading the feed used to raise the global busy flag, so with a live
     * subscription open the progress bar was on almost permanently — reporting
     * background traffic the user could neither wait for nor act on. Only an
     * explicit pull-to-refresh sets this, and only the feed's own indicator
     * reads it.
     */
    private val refreshingState = MutableStateFlow(false)

    /** Authors seen streaming past whose profile is not cached yet. */
    private val pendingProfiles = MutableStateFlow<Set<PubKey>>(emptySet())

    /** A relay whose details the user asked to see, from somewhere other than the relay list. */
    private val relayFocusState = MutableStateFlow<RelayUrl?>(null)
    private val mediaFocusState = MutableStateFlow<MediaHost?>(null)
    private val messageState = MutableStateFlow<UserMessage?>(null)
    private val feedState = MutableStateFlow(FeedState())
    private val viewedProfileState = MutableStateFlow<ViewedProfile?>(null)
    private val articleState = MutableStateFlow<List<Article>>(emptyList())
    private val relayInfoPromptState = MutableStateFlow<RelayUrl?>(null)
    private val revealedKeyState = MutableStateFlow<String?>(null)
    private val relayListPromptState = MutableStateFlow(false)

    /** True while an older page of somebody's posts is being fetched. */
    private val loadingMoreState = MutableStateFlow(false)

    /** True while the profile on screen is being re-read, for its own indicator. */
    private val refreshingProfileState = MutableStateFlow(false)

    /**
     * Authors whose relays have nothing older left to give.
     *
     * Recorded rather than inferred from a short page: a relay may return fewer
     * events than asked for and still hold more, but a page that added nothing
     * at all is the end of what the approved relays will hand over, and the
     * button says so rather than sitting there doing nothing when pressed.
     */
    private val exhaustedAuthorsState = MutableStateFlow<Set<PubKey>>(emptySet())

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

    /**
     * The open REQ behind the feed, or null when nothing is streaming.
     *
     * This is what keeps relay sockets up. Every read in this app used to be a
     * one-shot fetch, which the pool retires at EOSE; with no subscription left
     * alive it had no reason to hold a connection, so the relay count climbed
     * during a refresh and fell straight back to zero. Holding a subscription is
     * both the fix for that and how posts arrive without the user asking.
     */
    private var streamJob: Job? = null

    /** True between [onEnterForeground] and [onLeaveForeground]. */
    private var foreground = false

    /**
     * An account signed in while onboarding is still on screen, waiting for it
     * to finish.
     *
     * Relay grants outlive both the session and the account: they are persisted,
     * and logging out does not withdraw them. So the second time somebody
     * reaches "Where should we start?" — after a log out, or on any later launch
     * — the app already has relays it is allowed to talk to, and the load that
     * follows creating an account had somewhere to go. It went there, while a
     * screen saying "Wayfarer has not contacted anything yet" was up.
     *
     * `restartStream` has always refused to open a subscription during
     * onboarding for exactly this reason. The one-shot loads were gated on relay
     * permissions alone, which is a weaker rule than the one the screen states.
     * Now they wait here instead of being dropped: the work still happens, on
     * the far side of the question.
     */
    private var deferredSignIn: Account? = null

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

    val refreshing: StateFlow<Boolean> = refreshingState.asStateFlow()

    /** Whether there is anywhere for the back button to go. */
    val canGoBack: StateFlow<Boolean> = canGoBackState.asStateFlow()
    val message: StateFlow<UserMessage?> = messageState.asStateFlow()
    val feed: StateFlow<FeedState> = feedState.asStateFlow()
    val viewedProfile: StateFlow<ViewedProfile?> = viewedProfileState.asStateFlow()

    /** True while "load more" is fetching an older page. */
    val loadingMore: StateFlow<Boolean> = loadingMoreState.asStateFlow()

    /**
     * True while a profile is being re-read because somebody pulled it down.
     *
     * Separate from [refreshing], which belongs to the feed: the two indicators
     * are on different screens and a pull on one must not spin the other.
     */
    val refreshingProfile: StateFlow<Boolean> = refreshingProfileState.asStateFlow()

    /** Authors with nothing older left on the relays this app may read. */
    val exhaustedAuthors: StateFlow<Set<PubKey>> = exhaustedAuthorsState.asStateFlow()
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

    /** The installed signer's own name, when this device has one and it says. */
    val externalSignerLabel: String? get() = externalSignerName()

    /** True when this device can scan a QR code, which hides the button when it cannot. */
    val qrScanAvailable: Boolean get() = qrScan() != null

    val account: StateFlow<Account?> get() = core.accounts.account

    /** Every account signed in on this device, for the switcher in settings. */
    val accounts: StateFlow<List<AccountSummary>> get() = core.accounts.accounts

    /** The relays this build ships with. Shown before they are ever queried. */
    val suggestedRelays: List<RelayUrl> get() = core.suggestedRelays

    val relays = RelayController(core, scope, { messageState.value = it }, ::onRelayPermissionsChanged)

    /**
     * The other permission list: which servers may be asked for a picture.
     *
     * Its own view model rather than a corner of [relays], because they are two
     * different questions and confusing them is the mistake the whole relay
     * screen is written to prevent.
     */
    val media = MediaController(core, scope, { messageState.value = it }, describe = ::displayName)

    /**
     * The account's public NIP-65 list, which is not the same thing as [relays]
     * and is kept in its own view model so the two cannot drift into one screen.
     */
    val relayList = RelayListController(core, scope, { messageState.value = it }, ::recomputeRelayListPrompt)

    /** The Global screen: which mode, whose posts, in what order. */
    val global = GlobalController(core, scope, ::onBrowseSubjectChanged)

    /** Conversations under notes and articles, and which are open. */
    val threads = ThreadController(core, scope) { messageState.value = it }

    val connectedRelays: StateFlow<Set<RelayUrl>> get() = core.transport.connected

    init {
        startProfileBatching()

        // Restoring reads the keystore and then talks to relays, either of which
        // can fail. Without this the failure disappears into the SupervisorJob and
        // the user is left on a blank signed-out screen with no explanation.
        //
        // Quiet, because none of it was asked for. Reading the keystore decides
        // which screen opens and takes a moment; everything after it is relays
        // answering in their own time, and holding the progress bar — and with
        // it every button the bar disables — until they do is how the first
        // screen of the app came to be a screen you had to wait out.
        quietly {
            val restored = core.accounts.restore()
            // Whoever this session belongs to, and what they have allowed. A
            // guest gets an empty list that is never written down, which is why
            // the question below has to be asked again on every launch.
            core.scopePermissionsTo(restored?.pubKey)

            when {
                // Nobody has been here before: the introduction, from the top.
                restored == null && !core.onboarding.isComplete() -> onboardingState.value = OnboardingStep.Start

                // Somewhere to begin is the one thing this app cannot proceed
                // without, and a session that may reach nothing has not been
                // given one. A user who went through the introduction is not
                // sent back through it — they are asked the single question that
                // is still open.
                core.relayDirectory.grants.isEmpty() -> {
                    introduced = true
                    onboardingState.value = OnboardingStep.EntryPoint
                }

                else -> introduced = true
            }

            // Deferred while an onboarding step is up, and released the moment
            // it ends. loadFeed refuses outright during onboarding, so the guest
            // branch needs no guard of its own.
            if (restored != null) loadSignedInAccount(restored) else loadFeed()
        }
    }

    // ---- navigation -------------------------------------------------------

    fun go(destination: Screen) {
        // Only a real move earns a history entry, but the navigation itself
        // always runs: it is what takes a revealed key off the screen, and
        // skipping that for a no-op move would leave the key up.
        if (destination != screenState.value) backStack.addLast(screenState.value)
        navigateTo(destination)
    }

    /**
     * Switches tab, discarding history.
     *
     * A tab is a place you start from, not a step in a journey: stacking them
     * would mean back walked you through every tab you had visited before
     * leaving the app.
     */
    fun goToRoot(destination: Screen) {
        backStack.clear()
        navigateTo(destination)
    }

    /**
     * Steps back. Returns false when there is nowhere left to go, which is the
     * signal to let the system close the app.
     */
    fun back(): Boolean {
        backStack.removeLastOrNull()?.let {
            navigateTo(it)
            return true
        }
        // A screen reached without history still has somewhere sensible to go.
        if (screenState.value != Screen.Home) {
            navigateTo(Screen.Home)
            return true
        }
        return false
    }

    private fun navigateTo(destination: Screen) {
        // Leaving settings takes the key off screen with it: it is shown for as
        // long as it is being read, and not one navigation longer.
        if (destination !is Screen.Settings) revealedKeyState.value = null
        screenState.value = destination
        canGoBackState.value = backStack.isNotEmpty() || destination != Screen.Home
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

    /**
     * The back gesture, inside onboarding.
     *
     * Onboarding is a sequence rather than a set of destinations, so it cannot
     * share the screen back stack. [OnboardingStep.Backup] is absent on purpose:
     * the caller swallows the press there, because that screen is the only
     * showing of the secret key and leaving it early loses the account.
     */
    fun onboardingBack() {
        onboardingState.value =
            when (val step = onboardingState.value) {
                null, is OnboardingStep.Backup -> return
                OnboardingStep.Start -> {
                    if (canLeaveOnboarding) leaveOnboarding()
                    return
                }
                is OnboardingStep.Learn ->
                    if (step.page == 0) OnboardingStep.Start else OnboardingStep.Learn(step.page - 1)
                OnboardingStep.AccountChoice -> OnboardingStep.Learn(Introduction.pages.lastIndex)
                OnboardingStep.EntryPoint -> OnboardingStep.AccountChoice
                is OnboardingStep.ApproveRelays -> OnboardingStep.EntryPoint
            }
    }

    /** Backs out of the sign-in screen, for a guest who opened it and changed their mind. */
    fun leaveOnboarding() {
        if (!introduced) return
        onboardingState.value = null
        // Whoever signed in while this surface was up is now in the app, so
        // whatever was waiting on the far side of it can go ahead.
        val waiting = deferredSignIn
        deferredSignIn = null
        when (waiting) {
            null ->
                // Backing out of "add another account" with nobody new: the
                // feed's subscription was closed for the duration, because
                // nothing is contacted during onboarding, so it is reopened.
                reloadQuietly()
            else -> quietly { onSignedIn(waiting) }
        }
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
            handleEntryPoint(scanned, wasScanned = true)
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

    /**
     * [wasScanned] is the difference between a code and a typed address.
     *
     * Typing a relay address is itself the act of reading it, so approving what
     * was typed adds nothing. A scan is the opposite: the user has pointed a
     * camera at something and the app has decoded a host they have never seen.
     * So a scanned relay goes through the same consent screen an `nprofile`'s
     * hints already go through — named first, approved second — rather than
     * being granted and dialled on the strength of the scan.
     */
    private suspend fun handleEntryPoint(
        input: String,
        wasScanned: Boolean = false,
    ) {
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
                    OnboardingStep.ApproveRelays(purpose, hints, RelayOrigin.NamedByLink)
                }
            return
        }

        if (looksLikeRelay(trimmed)) {
            if (wasScanned) {
                // Normalized so the user is shown the address the app would
                // actually dial, but not approved: the grant is approveProposedRelays,
                // one screen and one press later.
                core.normalizer.normalize(trimmed)?.let { url ->
                    onboardingState.value =
                        OnboardingStep.ApproveRelays(RelayPurpose.Browse, listOf(url), RelayOrigin.Scanned)
                    return
                }
            } else {
                val url = core.addRelay(trimmed, read = true, write = false)
                if (url != null) {
                    // The account load released by completeOnboarding ends in a feed
                    // load of its own, so asking for one here as well would be the
                    // same query twice on the first screen after setup.
                    if (!completeOnboarding()) loadFeed()
                    return
                }
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
        OnboardingStep.ApproveRelays(purpose, core.suggestedRelays, RelayOrigin.AppDefaults)

    private suspend fun continueWith(purpose: RelayPurpose) {
        when (purpose) {
            is RelayPurpose.FindPerson -> {
                completeOnboarding()
                openProfileNow(purpose.pubKey)
            }
            is RelayPurpose.FindAccount -> {
                // The relays are approved, so the app can open; what it finds
                // about this account follows on its own. Same split as
                // afterSignIn, and for the same reason.
                completeOnboarding()
                core.accounts.account.value?.let(::loadSignedInAccount)
            }
            RelayPurpose.Browse -> {
                if (!completeOnboarding()) loadFeed()
            }
        }
    }

    /**
     * Ends onboarding, and releases whatever was waiting for it to end.
     *
     * Returns true when a signed-in account's load has just been started, so a
     * caller that would otherwise load the feed itself does not ask for the same
     * thing twice.
     */
    private suspend fun completeOnboarding(): Boolean {
        introduced = true
        core.onboarding.markComplete()
        onboardingState.value = null
        goToRoot(Screen.Home)
        recomputeRelayListPrompt()

        val waiting = deferredSignIn ?: return false
        deferredSignIn = null
        quietly { onSignedIn(waiting) }
        return true
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
            // Scoped before the questions rather than after them: every relay
            // approved during the rest of onboarding has to be written down as
            // *this* account's, or the load released at the end would replace
            // them with the empty list it was signed up with.
            core.scopePermissionsTo(account.pubKey)
            onboardingState.value = OnboardingStep.Backup(nsec)
            // Neither awaited nor started here. The account exists and the key
            // is on screen, which is what the press asked for; the reading waits
            // until onboarding is over, because there are still questions on
            // screen about what this app may talk to.
            loadSignedInAccount(account)
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
     * Loads what an account has on the network — but not while the user is still
     * being asked what may be contacted.
     *
     * The rule the whole onboarding surface rests on: nothing is contacted until
     * the questions are answered. See [deferredSignIn] for what used to happen
     * instead.
     */
    private fun loadSignedInAccount(account: Account) {
        if (onboardingState.value != null) {
            deferredSignIn = account
            return
        }
        quietly { onSignedIn(account) }
    }

    /**
     * With nothing approved, finding out who this account *is* — its profile,
     * follows and relay list — means querying relays the user has not chosen. So
     * that is asked for rather than done.
     */
    private suspend fun afterSignIn(account: Account) {
        // Their list, before it is asked whether the list is empty. Read before
        // the scope changed, the question would have been answered by whatever
        // the *previous* session — a guest, or somebody else — had allowed.
        core.scopePermissionsTo(account.pubKey)

        if (core.relayDirectory.grants.isEmpty()) {
            onboardingState.value = proposeDefaults(RelayPurpose.FindAccount(account.pubKey))
            return
        }
        // The app opens now; what this account has out there arrives when it
        // arrives. See createAccount for why that is not held under the bar.
        completeOnboarding()
        loadSignedInAccount(account)
    }

    /**
     * Ends the session: nothing signed in, nothing connected, back to the front
     * door.
     *
     * Deliberately not wrapped in [run]. Logging out was disabled whenever the
     * app happened to be loading something, which is exactly backwards — the one
     * control a person reaches for when they want the app to stop was the one
     * the app switched off while it was busy. It also left every socket open and
     * the feed subscription running under an onboarding screen, so "logged out"
     * meant the account was forgotten while the connections it opened carried on.
     *
     * The order matters: the network stops first, then what is on screen, and
     * only then the stored state. Nothing here suspends before the user is off
     * the app, so the screen changes on the same frame as the press.
     *
     * With another account signed in, this is a departure from one identity
     * rather than from the app: that account takes over. With none, the session
     * is left signed out, connected to nothing, at the front door.
     */
    fun logout() {
        val leaving = core.accounts.account.value?.pubKey
        stopEverything()
        clearSessionState()
        goToRoot(Screen.Home)

        quietly {
            // Erased, not put away: nothing of an account that has left this
            // phone is kept — its permissions, its private follow list and, in
            // AccountManager, its key. Somebody else's device must not be able
            // to resume any of it by signing back in.
            core.contacts.clear()
            leaving?.let { core.forgetEverythingAbout(it) }
            clearImageCache()?.invoke()

            when (val next = core.accounts.logout()) {
                // Somebody else is still signed in, so this is a departure from
                // one account rather than from the app.
                null -> {
                    core.scopePermissionsTo(null)
                    onboardingState.value = OnboardingStep.Start
                }
                else -> onSignedIn(next)
            }
        }
    }

    /**
     * Makes another signed-in account the active one.
     *
     * Not a logout: nothing is erased, and the account being left keeps its
     * permissions, its follows and its key. What changes is whose consent the
     * app is operating under, which is why every connection is dropped first —
     * the sockets that are open belong to the account that opened them.
     */
    fun switchTo(pubKey: PubKey) {
        if (core.accounts.account.value?.pubKey == pubKey) return
        stopEverything()
        clearSessionState()
        goToRoot(Screen.Home)

        quietly {
            core.contacts.clear()
            core.localFollows.clear()
            val account = core.accounts.switchTo(pubKey) ?: return@quietly
            onSignedIn(account)
        }
    }

    /** Adds another account without leaving the one signed in. */
    fun addAnotherAccount() {
        onboardingState.value = OnboardingStep.Start
    }

    /** Drops every connection this session has open. */
    private fun stopEverything() {
        streamJob?.cancel()
        streamJob = null
        if (transportStarted) {
            transportStarted = false
            core.transport.stop()
        }
    }

    /** Empties what is on screen, so nothing of one account is shown under another. */
    private fun clearSessionState() {
        feedState.value = FeedState()
        viewedProfileState.value = null
        revealedKeyState.value = null
        relayListPromptState.value = false
        exhaustedAuthorsState.value = emptySet()
        pendingProfiles.value = emptySet()
        threads.clear()
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
        // Their own permissions, before anything is asked of anybody. This is
        // also what stops one account inheriting another's: the list is keyed by
        // the pubkey that granted it.
        core.scopePermissionsTo(account.pubKey)
        ensureTransportStarted()

        core.relayListRepo.refresh(account.pubKey)?.let { core.relayListRepo.offerToDirectory(it, isOwnAccount = true) }
        core.profiles.load(account.pubKey)
        core.contacts.load(account.pubKey)
        core.localFollows.load(account.pubKey)

        loadFeed()
        recomputeRelayListPrompt()
    }

    // ---- feed -------------------------------------------------------------

    /**
     * A refresh the user asked for.
     *
     * The only feed load that shows anything. Everything else — signing in,
     * returning to the foreground, a permission changing — reloads silently,
     * because a progress bar that reports background traffic is a progress bar
     * the user learns to ignore.
     */
    fun refreshFeed() {
        if (refreshingState.value) return
        global.reshuffle()
        // Set before launching, not inside: the indicator answers the gesture,
        // and a frame where the user has pulled but nothing is spinning reads as
        // the pull having missed.
        refreshingState.value = true
        scope.launch {
            try {
                loadFeed()
            } catch (failure: Throwable) {
                messageState.value = UserMessage.Error(failure.message ?: "Could not refresh")
            } finally {
                refreshingState.value = false
            }
        }
    }

    /** Reloads without telling the user, for everything they did not ask for. */
    private fun reloadQuietly() {
        scope.launch {
            try {
                loadFeed()
            } catch (failure: Throwable) {
                messageState.value = UserMessage.Error(failure.message ?: "Could not load the feed")
            }
        }
    }

    /** The Global screen moved to another person or relay: fetch what it needs. */
    private fun onBrowseSubjectChanged() = reloadQuietly()

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
        // The same rule restartStream has always obeyed, now obeyed by the loads
        // as well: onboarding is the conversation about what may be contacted,
        // and nothing may be contacted while it is still going on. Gating this
        // on relay permissions alone was weaker than the rule, because
        // permissions outlive the session that granted them.
        if (onboardingState.value != null) return

        ensureTransportStarted()

        val me = core.accounts.account.value
        val follows = core.follows.now
        val browsing = global.currentMode == BrowseMode.Relay

        val result =
            if (browsing) {
                // One relay, because Relay mode is looking at one relay. Reading
                // every approved relay at once would fill the pager with posts
                // from servers the user is not currently looking at.
                global.currentRelay?.let { core.feed.loadFromRelays(setOf(it)) }
            } else if (follows.isNotEmpty()) {
                // Everyone, not just the person on screen: the rotation is
                // ordered by who posted most recently, so it needs to know about
                // all of them, and paging is then instant.
                core.feed.load(follows + setOfNotNull(me?.pubKey))
            } else {
                null
            }

        // Top up whoever the arrows have landed on. Their own relays are the
        // best place to ask, which is what a single-author load routes to.
        if (!browsing) global.currentPerson?.let { core.feed.load(setOf(it)) }

        core.profiles.loadAll(
            result?.notes.orEmpty().mapTo(mutableSetOf()) { it.author } + follows + setOfNotNull(me?.pubKey),
        )

        articleState.value = core.articles.all.value.values.sortedByDescending { it.publishedAt }

        feedState.value =
            FeedState(
                notes = result?.notes.orEmpty(),
                unreachableAuthors = result?.unreachableAuthors.orEmpty(),
                guessedAuthors = result?.guessedAuthors.orEmpty(),
                relaysQueried = result?.relaysQueried.orEmpty(),
                browsingRelays = if (browsing) result?.relaysQueried.orEmpty() else emptySet(),
                profiles = core.profiles.profiles.value,
                loaded = true,
            )

        restartStream()
    }

    private fun ensureTransportStarted() {
        if (transportStarted) return
        transportStarted = true
        core.transport.start()
    }

    // ---- streaming --------------------------------------------------------

    /**
     * Opens — or reopens — the feed's live subscription.
     *
     * Called after every load, because the routing plan is derived from the
     * follows and permissions that load just used: a new grant or a new follow
     * changes which relays should be asked, and the old REQ would go on asking
     * the old ones.
     *
     * Deliberately not wrapped in [run]: a subscription stays open, and [run]
     * would hold the busy indicator on for as long as it did.
     */
    private fun restartStream() {
        streamJob?.cancel()
        streamJob = null

        // The same consent rule the rest of the app obeys: onboarding is a
        // conversation about what may be contacted, and nothing may be contacted
        // while it is still going on.
        if (onboardingState.value != null || !foreground) return


        val me = core.accounts.account.value
        val follows = core.follows.now
        val browsing = global.currentMode == BrowseMode.Relay
        val relay = global.currentRelay
        if (browsing && relay == null) return
        if (!browsing && follows.isEmpty()) return

        // Only what is new. The backlog is [loadFeed]'s job, and asking for it
        // again here would have every relay replay the same notes on reconnect.
        val since = core.clock.nowSeconds()

        streamJob =
            scope.launch {
                val stream =
                    if (browsing) {
                        core.feed.liveFromRelays(setOf(relay!!), since)
                    } else {
                        core.feed.live(follows + setOfNotNull(me?.pubKey), since)
                    }
                stream.collect { note -> onStreamedNote(note) }
            }
    }

    /**
     * Merges one streamed note into the feed, newest first and without duplicates.
     *
     * Nothing here waits on the network. This runs inside the subscription's
     * collector, so a fetch in it stalls every note behind this one: looking up
     * an unknown author's profile inline meant one round trip per note, each
     * with a ten-second idle timeout, serialized. Authors are queued instead and
     * resolved in batches by [startProfileBatching].
     */
    private fun onStreamedNote(note: Note) {
        if (note.author !in core.profiles.profiles.value) {
            pendingProfiles.value = pendingProfiles.value + note.author
        }

        // Long-form events ride the same REQ, so anything new is already in the
        // article store by the time this runs.
        articleState.value = core.articles.all.value.values.sortedByDescending { it.publishedAt }

        val current = feedState.value
        // A note can arrive from several relays; absorb() merges provenance, so
        // the copy arriving now is the more complete one and replaces the earlier.
        val merged =
            (listOf(note) + current.notes.filterNot { it.id == note.id })
                .sortedByDescending { it.createdAt }

        feedState.value =
            current.copy(
                notes = merged,
                profiles = core.profiles.profiles.value,
                loaded = true,
            )
    }

    /**
     * Resolves queued author profiles, a batch at a time.
     *
     * Parks on an empty queue, so it costs nothing when nothing is streaming.
     * The delay is what does the work: a burst of strangers arriving together
     * becomes one query rather than one per note, and [ProfileRepository.loadAll]
     * already drops the authors it has and asks each relay once.
     */
    private fun startProfileBatching() {
        scope.launch {
            while (true) {
                pendingProfiles.first { it.isNotEmpty() }
                delay(PROFILE_BATCH_DELAY_MS)
                val batch = pendingProfiles.value
                if (batch.isEmpty()) continue
                pendingProfiles.value = emptySet()
                try {
                    core.profiles.loadAll(batch)
                    recordRelayHints()
                    feedState.value = feedState.value.copy(profiles = core.profiles.profiles.value)
                } catch (failure: Throwable) {
                    // A name that could not be fetched is shown as an abbreviated
                    // key. Not worth interrupting the reader over.
                    pendingProfiles.value = pendingProfiles.value + batch
                    delay(PROFILE_BATCH_DELAY_MS)
                }
            }
        }
    }

    /**
     * Files the relay hints noticed while reading.
     *
     * Drained here rather than at the point each event is absorbed, because that
     * runs inside the subscription's collector where a suspending write would
     * hold up every event behind it.
     */
    private suspend fun recordRelayHints() {
        for ((reason, raw) in core.relayHints.drain()) {
            val urls = raw.mapNotNull(core.normalizer::normalize).distinct()
            if (urls.isNotEmpty()) core.relayDirectory.note(urls, reason)
        }
    }

    // ---- app lifecycle ----------------------------------------------------

    /**
     * The app came to the front: bring the transport up and start streaming.
     *
     * Sockets are tied to the foreground on purpose. An app whose promise is that
     * it contacts nothing you did not allow should also not sit holding
     * connections to those relays while nobody is looking at it.
     */
    fun onEnterForeground() {
        if (foreground) return
        foreground = true
        if (onboardingState.value != null) return
        // A reload rather than only reopening the REQ. The subscription asks for
        // what happens from now on, so resuming without one would leave whatever
        // was published while the app was away missing until the user pulled to
        // refresh. loadFeed reopens the stream on its way out.
        reloadQuietly()
    }

    /**
     * The app went to the background: close the REQ, drop the sockets, and put
     * the secret key away.
     *
     * The key is cleared here for the same reason `SecureScreen` exists: leaving
     * the app is exactly when the system takes the snapshot the task switcher
     * shows, and coming back would otherwise re-display the key without asking
     * again. Navigating away already clears it; leaving is the other way off the
     * screen and was the one not covered.
     */
    fun onLeaveForeground() {
        if (!foreground) return
        foreground = false
        revealedKeyState.value = null
        streamJob?.cancel()
        streamJob = null
        if (!transportStarted) return
        // Reset the latch, or the transport could never be brought back up.
        transportStarted = false
        core.transport.stop()
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
                back()
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
        recomputeRelayListPrompt()
        reloadQuietly()
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
        go(Screen.RelayList)
        relayList.load()
    }

    /** What [pubKey] advertises, from the cache outbox routing already fills. */
    fun advertisedRelaysFor(pubKey: PubKey) = relayList.advertisedBy(pubKey)

    fun openProfile(pubKey: PubKey) =
        run {
            openProfileNow(pubKey)
        }

    /** The Local tab's own destination, so back leaves rather than unwinding. */
    fun openProfileAsRoot(pubKey: PubKey) =
        run {
            openProfileNow(pubKey, asRoot = true)
        }

    private suspend fun openProfileNow(
        pubKey: PubKey,
        asRoot: Boolean = false,
    ) {
        // The slot is claimed before anything suspends, so the screen that
        // composes on the very next frame already knows who it is about and
        // does not start a second load of its own.
        viewedProfileState.value = ViewedProfile(pubKey, core.profiles[pubKey], emptyList(), loading = true)
        if (asRoot) goToRoot(Screen.Profile(pubKey)) else go(Screen.Profile(pubKey))
        loadProfileNow(pubKey)
    }

    /**
     * Makes the profile screen's state be about [pubKey].
     *
     * Called by the screen itself on arrival as well as by [openProfileNow],
     * and that is the fix for posts that vanished on the way *back* to somebody.
     * [viewedProfileState] is a single slot: opening Alice, then Bob, then
     * pressing back left the screen showing Alice with Bob's state in the slot,
     * which the screen correctly refused to draw — so her posts disappeared and
     * nothing ever put them back, because the only thing that loaded a profile
     * was the tap that navigated to it.
     */
    fun ensureProfileShown(pubKey: PubKey) {
        if (viewedProfileState.value?.pubKey == pubKey) return
        run { loadProfileNow(pubKey) }
    }

    /**
     * Re-reads the profile on screen, for the pull gesture.
     *
     * The same load, with its own indicator and without the busy bar: a pull is
     * a question about *this* person, and the app-wide progress line answers a
     * different one.
     */
    fun refreshProfile(pubKey: PubKey) {
        if (refreshingProfileState.value) return
        refreshingProfileState.value = true
        scope.launch {
            try {
                // A fresh look, so the offer to load older posts comes back too.
                exhaustedAuthorsState.value = exhaustedAuthorsState.value - pubKey
                loadProfileNow(pubKey)
            } catch (failure: Throwable) {
                messageState.value = UserMessage.Error(failure.message ?: "Could not refresh this profile")
            } finally {
                refreshingProfileState.value = false
            }
        }
    }

    private suspend fun loadProfileNow(pubKey: PubKey) {
        ensureTransportStarted()
        // A fresh look: somebody who had nothing older last time may have posted
        // since, and their relays may have been approved since. Either way the
        // offer to load more comes back.
        exhaustedAuthorsState.value = exhaustedAuthorsState.value - pubKey
        viewedProfileState.value = ViewedProfile(pubKey, core.profiles[pubKey], emptyList(), loading = true)

        core.profiles.load(pubKey)
        // Where this person says they can be paid (NIP-A3). Fetched with the
        // profile because that is the only screen that shows it, and skipped
        // entirely for everybody whose profile is never opened.
        core.payments.load(pubKey)
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

    /**
     * Everything held by [author], newest first.
     *
     * Read from the note store rather than from the snapshot [ViewedProfile]
     * took, so a profile shows what the app actually has at the moment it draws:
     * posts that arrive on the open subscription appear without a reload, and —
     * the bug this fixes — a load that is still in flight no longer blanks the
     * posts already on screen.
     */
    fun notesBy(author: PubKey): List<Note> =
        core.feed.allNotes.value.values
            .filter { it.author == author }
            .sortedByDescending { it.createdAt }

    /**
     * Fetches an older page of [author]'s posts.
     *
     * Not a bigger limit on the same query: a relay answers a limit with its
     * *newest* events, so asking for a hundred instead of fifty re-reads the
     * same fifty and adds whatever the next fifty happen to be. This asks for
     * what came before the oldest post already held, which is the only request
     * that reaches further back — and it is a press rather than an automatic
     * fetch at the bottom of the list, because each one is another round of
     * queries to somebody else's servers.
     */
    fun loadMoreFrom(author: PubKey) {
        if (loadingMoreState.value || author in exhaustedAuthorsState.value) return
        scope.launch {
            loadingMoreState.value = true
            try {
                val oldest = oldestHeldFor(author)
                val result =
                    core.feed.load(
                        authors = setOf(author),
                        limitPerRelay = PAGE_SIZE,
                        // NIP-01's `until` is inclusive, so starting at the
                        // oldest post held would spend the page re-reading it.
                        until = oldest?.minus(1),
                    )

                if (result.added == 0) exhaustedAuthorsState.value = exhaustedAuthorsState.value + author

                articleState.value = core.articles.all.value.values.sortedByDescending { it.publishedAt }

                // The Global screen derives its list from the note cache and has
                // updated itself already; the profile screen holds a snapshot.
                val viewed = viewedProfileState.value
                if (viewed?.pubKey == author) viewedProfileState.value = viewed.copy(notes = result.notes)
            } catch (failure: Throwable) {
                messageState.value = UserMessage.Error(failure.message ?: "Could not load older posts")
            } finally {
                loadingMoreState.value = false
            }
        }
    }

    /**
     * The oldest thing held for [author], across both stores.
     *
     * Articles count: they arrive on the same subscription and are shown in the
     * same profile, so paging past the oldest note while an older article sits
     * unfetched would stop short of it.
     */
    private fun oldestHeldFor(author: PubKey): Long? {
        val oldestNote = core.feed.allNotes.value.values.filter { it.author == author }.minOfOrNull { it.createdAt }
        val oldestArticle = core.articles.all.value.values.filter { it.author == author }.minOfOrNull { it.createdAt }
        return when {
            oldestNote == null -> oldestArticle
            oldestArticle == null -> oldestNote
            else -> minOf(oldestNote, oldestArticle)
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
                    back()
                    openProfileNow(me.pubKey)
                }
                is PublishResult.Failure -> messageState.value = result.error.toMessage()
            }
        }

    /** Every payment target list this app has seen, keyed by whose it is. */
    val paymentTargets: StateFlow<Map<PubKey, List<PaymentTarget>>> get() = core.payments.targets

    /**
     * Publishes the account's own kind 10133.
     *
     * Its own action rather than part of [saveProfile], because it is its own
     * event: a profile is a kind 0 and payment targets are a kind 10133, they
     * replace different things on a relay, and one button doing both would be
     * two publishes reported as one.
     */
    fun savePaymentTargets(targets: List<PaymentTarget>) =
        run {
            val me = core.accounts.account.value ?: return@run
            val signer = core.accounts.signer ?: return@run
            when (val result = core.payments.publish(signer, me.pubKey, targets)) {
                is PublishResult.Success -> messageState.value = UserMessage.Published(result.report)
                is PublishResult.Failure -> messageState.value = result.error.toMessage()
            }
        }

    fun profileFor(pubKey: PubKey): Profile? = core.profiles[pubKey]

    /**
     * Every profile this app has seen, as a flow.
     *
     * [profileFor] peeks at the same map without one, so a composable built on it
     * never recomposes when a kind 0 lands — which is exactly the bug the
     * advertised-relay card hit before it moved to the reactive `relayLists`.
     * Avatars need the picture the moment it arrives, so they read this instead.
     */
    val profiles: StateFlow<Map<PubKey, Profile>> get() = core.profiles.profiles

    // ---- what is wrong with what was typed --------------------------------

    /**
     * Why [input] is not a key, or null when it is one.
     *
     * Checked before anything is attempted, so the answer can be shown against
     * the field that produced it rather than as a banner over the whole app. The
     * parsing is the same the login itself does — this asks core the question
     * without acting on it.
     */
    fun keyProblem(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "Paste an npub or an nsec."
        if (core.bech32.decodeSecKeyHex(trimmed) != null || core.bech32.decodePubKey(trimmed) != null) return null
        return "That is not an npub or an nsec. A raw hex key works too."
    }

    /** Why [input] is not somewhere to start, or null when it is. */
    fun startingPointProblem(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "Type a relay address, an npub or an nprofile."
        if (core.bech32.decodeProfileRef(trimmed) != null) return null
        if (core.normalizer.normalize(trimmed) != null) return null
        return "Not a relay address or a key. A relay looks like wss://relay.example.com."
    }

    /** Why [input] is not a relay address, or null when it is. */
    fun relayProblem(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "Type a relay address."
        if (core.normalizer.normalize(trimmed) != null) return null
        return "Not a relay address. They look like wss://relay.example.com."
    }

    /** Why [input] is not a picture server, or null when it is one. */
    fun mediaHostProblem(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "Type a server address."
        if (media.hostOf(trimmed) != null) return null
        return "Not a server address. They look like image.example.com."
    }

    fun npubFor(pubKey: PubKey): String = core.bech32.encodeNpub(pubKey)

    /**
     * The person a bech32 entity names, or null when it names something else.
     *
     * For the `nostr:` references NIP-23 says an article's prose is full of: an
     * `npub` or `nprofile` can be shown as a name, and a `note`, `nevent` or
     * `naddr` cannot, because it is a post rather than a person.
     */
    fun pubKeyOf(entity: String): PubKey? = core.bech32.decodeProfileRef(entity)?.pubKey

    /**
     * What to call somebody on screen.
     *
     * The single answer to "who wrote this", so an author without metadata
     * cannot be a shortened npub in one place and truncated hex in another —
     * which is what happened when each call site picked its own fallback.
     */
    /**
     * The relay the relay screen should open on arrival, or null.
     *
     * A one-shot rather than a parameter on [Screen.Relays]: the sheet's open
     * state lives inside the relay screen, and threading it through navigation
     * would mean going back and forward re-opening the sheet by itself.
     */
    val relayFocus: StateFlow<RelayUrl?> = relayFocusState.asStateFlow()

    /** Every NIP-65 list the app has seen, so a profile can react to one arriving. */
    val relayLists get() = core.relayLists.lists

    val headerStyle: StateFlow<HeaderStyle> get() = core.preferences.headerStyle

    fun setHeaderStyle(style: HeaderStyle) =
        scope.launch {
            core.preferences.setHeaderStyle(style)
        }

    /**
     * Opens one relay's details on the relay screen.
     *
     * Records it as pending first when nothing knows about it yet. A relay
     * somebody advertises that routing has never wanted appears in none of the
     * screen's three lists, and the sheet quietly closes on a relay it cannot
     * find a row for — so without this the tap would look broken.
     */
    fun openRelayDetail(
        url: RelayUrl,
        because: String,
    ) = run {
        if (core.relayDirectory.grants[url] == null &&
            core.relayDirectory.pending[url] == null &&
            url !in core.relayDirectory.snapshot.value.denied
        ) {
            core.relayDirectory.note(listOf(url), DiscoveryReason(DiscoverySource.EVENT_HINT, because))
        }
        relayFocusState.value = url
        go(Screen.Relays)
    }

    fun clearRelayFocus() {
        relayFocusState.value = null
    }

    /** Which media host the media screen should open, once. */
    val mediaFocus: StateFlow<MediaHost?> = mediaFocusState.asStateFlow()

    /**
     * Opens one media host's details on the media screen.
     *
     * Queues it first when nothing knows about it yet, for the same reason
     * [openRelayDetail] does: a host tapped from an avatar that has never been
     * drawn is in none of the screen's lists, and the sheet would close on a row
     * it could not find.
     */
    fun openMediaHost(
        host: MediaHost,
        because: String,
    ) = run {
        if (core.mediaDirectory.grants[host] == null &&
            core.mediaDirectory.pending[host] == null &&
            host !in core.mediaDirectory.snapshot.value.denied
        ) {
            core.mediaDirectory.note(listOf(host), MediaReason(MediaSource.AVATAR, because))
        }
        mediaFocusState.value = host
        go(Screen.Media)
    }

    fun clearMediaFocus() {
        mediaFocusState.value = null
    }

    /** What "active" means on the Global screen, in days. */
    val activityWindowDays: StateFlow<Int> get() = core.preferences.activityWindowDays

    fun setActivityWindowDays(days: Int) =
        scope.launch {
            core.preferences.setActivityWindowDays(days)
        }

    /** How old a post is, against the same clock the rest of the app uses. */
    fun timeAgo(createdAt: Long): String = formatTimestamp(createdAt, core.clock.nowSeconds())

    /** A calendar date, for the things that are dated rather than aged. */
    fun dateOf(epochSeconds: Long): String = formatDate(epochSeconds)

    /**
     * What a reply is answering, for the line above it.
     *
     * Names the parent's author when that post is already known, and says only
     * that it is a reply when it is not — fetching the parent of every reply on
     * screen would be a query per post, and the label is orientation rather than
     * something worth a round trip.
     */
    fun replyContextFor(parent: EventId): String {
        val author = authorOf(parent) ?: return "reply"
        return "reply to ${displayName(author)}"
    }

    /**
     * Every note held, whether it arrived as a post or as somebody's reply.
     *
     * Two stores because replies are kept apart from the feed — that separation
     * is what stopped them rendering as top-level posts — and a note opened on
     * its own screen can have come from either.
     */
    val allNotes: StateFlow<Map<EventId, Note>> get() = core.feed.allNotes

    val threadReplies: StateFlow<Map<EventId, Note>> get() = core.threads.allReplies

    /** Posts a conversation hangs from, fetched when its thread was opened. */
    val threadRoots: StateFlow<Map<EventId, Note>> get() = core.threads.threadRoots

    /**
     * An event id in the form other apps accept: `nostr:note1…`.
     *
     * Bare hex is what the id *is* and not what anything reads: NIP-21 says a
     * reference is the `nostr:` URI of a NIP-19 entity, so that is what Copy
     * puts on the clipboard — paste it into another client, or into a post, and
     * it resolves. The hex is still there for anyone who wants it, in the raw
     * JSON one item further down the same menu.
     */
    fun shareableEventId(id: EventId): String =
        runCatching { "nostr:" + core.bech32.encodeNote(id.hex) }
            // A key that will not encode is not worth losing the copy over.
            .getOrElse { id.hex }

    // ---- following ---------------------------------------------------------

    /** The public kind 3 list. */
    val publishedFollows: StateFlow<Set<PubKey>> get() = core.contacts.follows

    /** This phone's own list. */
    val localFollows: StateFlow<Set<PubKey>> get() = core.localFollows.follows

    /** Which list, or lists, somebody is on. Null when nobody is signed in. */
    fun followSourcesOf(pubKey: PubKey): Set<FollowSource> = core.follows.sourcesOf(pubKey)

    /**
     * Whether a public follow would be published from a list nobody has seen.
     *
     * A kind 3 names everybody at once, so republishing one built from an empty
     * cache replaces the real list everywhere. The screen says so; it does not
     * refuse, because the alternative is a follow button that silently does
     * nothing on a cold start.
     */
    val publicFollowListKnown: Boolean get() = core.contacts.loaded

    /** Adds somebody to this phone's own list. Published nowhere. */
    fun followLocally(pubKey: PubKey) =
        run {
            if (core.accounts.account.value == null) {
                messageState.value = UserMessage.Error("Sign in to keep a follow list on this phone.")
                return@run
            }
            core.localFollows.add(pubKey)
        }

    fun unfollowLocally(pubKey: PubKey) = run { core.localFollows.remove(pubKey) }

    /** Adds somebody to the public kind 3 list and republishes it. */
    fun followPublicly(pubKey: PubKey) = changePublicFollow(pubKey, following = true)

    fun unfollowPublicly(pubKey: PubKey) = changePublicFollow(pubKey, following = false)

    private fun changePublicFollow(
        pubKey: PubKey,
        following: Boolean,
    ) = run {
        val me = core.accounts.account.value
        if (me == null) {
            messageState.value = UserMessage.Error("Sign in to change your public follow list.")
            return@run
        }
        val signer = core.accounts.signer
        if (signer == null || !signer.canSign) {
            messageState.value =
                UserMessage.Error("This account is watch-only, so it cannot publish a follow list. Follow on this phone instead.")
            return@run
        }
        val result =
            if (following) {
                core.contacts.follow(signer, me.pubKey, pubKey)
            } else {
                core.contacts.unfollow(signer, me.pubKey, pubKey)
            }
        when (result) {
            is PublishResult.Success -> messageState.value = UserMessage.Published(result.report)
            is PublishResult.Failure ->
                messageState.value =
                    UserMessage.Error("Your follow list could not be published: ${result.error.describe()}")
        }
    }

    // ---- one event's own actions -------------------------------------------

    /** The event behind a post, if the store still has it. */
    fun eventOf(id: EventId): NostrEvent? = core.events[id]

    /** The event as JSON, for showing or copying. Null when it is not held. */
    fun rawJsonOf(id: EventId): String? = core.events[id]?.let(core.codec::encodeEvent)

    /**
     * Relays this phone may send to.
     *
     * Write-approved only. Publishing is writing, and a relay approved for
     * reading was approved for exactly that — offering it here would be asking
     * the user to authorise a send by a different name.
     */
    fun rebroadcastTargets(): List<RelayUrl> =
        core.relayDirectory.grants.values
            .filter { it.write }
            .map { it.url }
            .sortedBy { it.display() }

    /**
     * Sends an event this app already holds to relays the user picked.
     *
     * Nothing is re-signed and nothing is rewritten: the same event, offered to
     * somewhere else that will carry it. Nostr has no other way to move a post
     * to a relay that never received it.
     */
    fun rebroadcast(
        id: EventId,
        relays: Set<RelayUrl>,
    ) {
        val event = core.events[id]
        if (event == null) {
            messageState.value = UserMessage.Error("That post's event is no longer held, so it cannot be sent again.")
            return
        }
        if (relays.isEmpty()) {
            messageState.value = UserMessage.Error("Choose at least one relay to send it to.")
            return
        }
        scope.launch {
            val outcomes = core.transport.publish(event, relays)
            val accepted = outcomes.count { it.value.accepted }
            messageState.value =
                if (accepted == 0) {
                    UserMessage.Error("No relay accepted it.")
                } else {
                    UserMessage.Info("Sent to $accepted of ${outcomes.size} ${if (outcomes.size == 1) "relay" else "relays"}.")
                }
        }
    }

    /**
     * Who wrote an event, if it has been seen.
     *
     * Replies are stored apart from the feed — that separation is what stopped
     * them rendering as top-level posts — so a thread root can be in either
     * store depending on whether it arrived as a post or as somebody's reply.
     */
    fun authorOf(id: EventId): PubKey? =
        core.feed.allNotes.value[id]?.author
            ?: core.threads.allReplies.value[id]?.author
            ?: core.threads.threadRoots.value[id]?.author
            ?: core.threads.allComments.value[id]?.author

    fun displayName(pubKey: PubKey): String =
        core.profiles[pubKey]?.displayNameOrNull() ?: shortenNpub(npubFor(pubKey))

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
                    back()
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

    /**
     * The same, without the progress bar.
     *
     * [busyState] disables buttons — every onboarding step, and Log out — so it
     * has to mean "the thing you just pressed is still happening" and nothing
     * else. Work that follows a press but outlives the answer to it belongs
     * here: fetching a newly signed-in account's profile, follows and feed takes
     * as long as somebody else's relays take, and while it ran the introduction
     * sat under a progress bar with its buttons dead, and Settings would not let
     * you log out.
     */
    private fun quietly(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (failure: Throwable) {
                messageState.value = UserMessage.Error(failure.message ?: failure::class.simpleName ?: "Something failed")
            }
        }
    }

}

/** How a failed publish is put to the user. Shared with [ThreadController]. */
fun PublishError.toMessage(): UserMessage =
    when (this) {
        PublishError.NotSignedIn -> UserMessage.Error("Sign in first.")
        PublishError.WatchOnlyAccount -> UserMessage.Error("This account was added with an npub, so it cannot publish.")
        PublishError.NoApprovedWriteRelay ->
            UserMessage.Error("No relay is approved for posting. Approve one for posting in Relays first.")
        is PublishError.Rejected -> UserMessage.Published(report)
    }

/** How many posts per relay one press of "load more" asks for. */
private const val PAGE_SIZE = 50

/** How long a burst of streamed authors is allowed to accumulate. */
private const val PROFILE_BATCH_DELAY_MS = 2_000L

sealed interface Screen {
    data object Home : Screen

    data object Relays : Screen

    /** Which servers may be asked for a picture. Not the relay list. */
    data object Media : Screen

    data object Compose : Screen

    data object EditProfile : Screen

    /** Account, key backup and the introduction, in one place. */
    data object Settings : Screen

    /** The account's own NIP-65 list: where other people should look for it. */
    data object RelayList : Screen

    /** Both follow lists, and what belongs to which. */
    data object Follows : Screen

    /** Null address is a new article; otherwise an edit that keeps the d tag. */
    data class EditArticle(
        val address: String?,
    ) : Screen

    data class ReadArticle(
        val address: String,
    ) : Screen

    /** One note, on its own, with its conversation already open. */
    data class ReadNote(
        val id: EventId,
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
     * [origin] is the difference between "the link you pasted names these", "you
     * pointed a camera at this one" and "we have nowhere else to look".
     */
    data class ApproveRelays(
        val purpose: RelayPurpose,
        val relays: List<RelayUrl>,
        val origin: RelayOrigin,
    ) : OnboardingStep
}

/**
 * Where the relays on a consent screen came from.
 *
 * Three answers rather than two, because a scanned code is not a typed one. A
 * person who types `wss://relay.example.com` has read it. A person who points a
 * camera at a sticker has not, and a QR code can encode any host at all.
 */
enum class RelayOrigin {
    /** The relays this build ships with. Wayfarer's own guess, and it says so. */
    AppDefaults,

    /** Carried by an `nprofile` the user opened. A claim by whoever wrote the link. */
    NamedByLink,

    /** Decoded from a QR code, and not yet seen by the person who scanned it. */
    Scanned,
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

/**
 * What the last feed load did, and what it cost.
 *
 * The four fields after [notes] are the load's own account of itself: which
 * relays were asked, whose posts could not be routed anywhere, and whose were
 * guessed at rather than routed. They are the read half of the transparency the
 * publish report already gives writes — a published note names every relay that
 * took it, and until now a *read* named none, though it is the read that
 * discloses who you follow.
 */
data class FeedState(
    val notes: List<Note> = emptyList(),
    val unreachableAuthors: Set<PubKey> = emptySet(),
    val guessedAuthors: Set<PubKey> = emptySet(),
    /**
     * The relays this load actually sent a request to.
     *
     * A set rather than a count: naming them is the point. These are the servers
     * that were handed the pubkeys being asked for, so "four relays" answers a
     * different and much weaker question than "these four".
     */
    val relaysQueried: Set<RelayUrl> = emptySet(),
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

/**
 * Something to tell the user, and how much of the screen it deserves.
 *
 * Two weights, not one. An error and a publish report used to be the same
 * full-width card pinned under the app bar, pushing the page down and staying
 * until dismissed — so a mistyped relay address interrupted as much as a report
 * naming eight relays, and neither could be ignored. [transient] messages are
 * snackbars now: they float over the content, leave on their own, and shift
 * nothing. A publish report is not transient, because it is a record somebody
 * reads rather than a flash.
 */
sealed interface UserMessage {
    /** True when this is a line to glance at rather than a page to read. */
    val transient: Boolean get() = true

    data class Error(
        val text: String,
    ) : UserMessage

    data class Info(
        val text: String,
    ) : UserMessage

    /** A publish result, shown per relay — the interesting part of outbox routing. */
    data class Published(
        val report: PublishReport,
    ) : UserMessage {
        override val transient: Boolean get() = false
    }
}
