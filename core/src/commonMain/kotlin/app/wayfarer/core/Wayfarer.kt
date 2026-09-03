package app.wayfarer.core

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.Bech32Codec
import app.wayfarer.core.nostr.KeyTool
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayInfoFetcher
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.nostr.RelayUrlNormalizer
import app.wayfarer.core.outbox.OutboxConfig
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.MediaDirectory
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.relay.RelayHintQueue
import app.wayfarer.core.relay.RelayInfoService
import app.wayfarer.core.repo.AccountManager
import app.wayfarer.core.repo.ArticleRepository
import app.wayfarer.core.repo.ContactRepository
import app.wayfarer.core.repo.EventStore
import app.wayfarer.core.repo.FeedRepository
import app.wayfarer.core.repo.FollowBook
import app.wayfarer.core.repo.LocalFollowStore
import app.wayfarer.core.repo.OnboardingStore
import app.wayfarer.core.repo.PaymentRepository
import app.wayfarer.core.repo.PreferencesStore
import app.wayfarer.core.repo.ProfileRepository
import app.wayfarer.core.repo.RelayListRepository
import app.wayfarer.core.repo.SignerFactory
import app.wayfarer.core.repo.ThreadRepository
import app.wayfarer.core.store.KeyValueStore
import app.wayfarer.core.store.MediaDirectoryCodec
import app.wayfarer.core.store.PersistedMediaDirectoryStore
import app.wayfarer.core.store.PersistedRelayDirectoryStore
import app.wayfarer.core.store.RelayDirectoryCodec
import app.wayfarer.core.store.SecretStore
import app.wayfarer.core.util.Clock

/**
 * Everything the platform must supply for the core to run.
 *
 * All of it is satisfied by the `nostr-quartz` module plus two small Android
 * storage classes. Swapping the nostr backend means providing this bundle from
 * somewhere else; nothing else in the core changes.
 */
data class NostrBackend(
    val codec: NostrCodec,
    val bech32: Bech32Codec,
    val keyTool: KeyTool,
    val normalizer: RelayUrlNormalizer,
    val signerFactory: SignerFactory,
    val clock: Clock,
    /** NIP-11. Only ever called from an explicit user action — see [RelayInfoService]. */
    val relayInfoFetcher: RelayInfoFetcher,
    /**
     * Built with the [RelayDirectory] this container creates, so the transport
     * can enforce the approval gate at the socket layer. Hence a factory rather
     * than a ready-made instance.
     */
    val transportFactory: (RelayDirectory) -> RelayTransport,
)

/**
 * The composition root of the app's logic. Holds every repository and wires them
 * together; knows nothing about Android, Compose, or any UI concept.
 *
 * Construct via [Wayfarer.create], which seeds the pending relay queue with the
 * bootstrap suggestions. Permissions themselves belong to an account and arrive
 * with [scopeRelaysTo].
 */
class Wayfarer private constructor(
    val relayDirectory: RelayDirectory,
    /**
     * The other permission list: which servers may be asked for a picture.
     *
     * Separate from [relayDirectory] rather than folded into it, because they
     * answer different questions and are wrong in different ways. A relay that
     * is not approved costs the user posts they wanted; a media host that is not
     * approved costs them a photograph and nothing else.
     */
    val mediaDirectory: MediaDirectory,
    val relayLists: RelayListCache,
    val router: OutboxRouter,
    val transport: RelayTransport,
    val accounts: AccountManager,
    val profiles: ProfileRepository,
    /** NIP-A3 payment targets, read with a profile and published from it. */
    val payments: PaymentRepository,
    val feed: FeedRepository,
    val articles: ArticleRepository,
    /** Conversations under a note or an article. Fetched on demand, not streamed. */
    val threads: ThreadRepository,
    val relayInfo: RelayInfoService,
    val contacts: ContactRepository,
    val relayListRepo: RelayListRepository,
    val normalizer: RelayUrlNormalizer,
    val bech32: Bech32Codec,
    /** Exposed for the one thing the UI needs from it: an event as its own JSON. */
    val codec: NostrCodec,
    val onboarding: OnboardingStore,
    /** Settings the user can change. */
    val preferences: PreferencesStore,
    /** Relay hints noticed while reading, waiting to be recorded. */
    val relayHints: RelayHintQueue,
    /** The events behind the projections, for showing one raw or sending it again. */
    val events: EventStore,
    /** People followed on this phone only, never published. */
    val localFollows: LocalFollowStore,
    /** Both follow lists as the one question the feed actually asks. */
    val follows: FollowBook,
    /**
     * The wall clock the repositories were built with.
     *
     * Exposed because the UI layer needs the same one — a live subscription is
     * opened with a `since` of "now", and taking that from a different clock than
     * the one stamping events would make the boundary drift under test.
     */
    val clock: Clock,
    /**
     * The relays this build ships with, normalized. Named here so onboarding can
     * say which relays it would have to query before it queries them — the list
     * outlives its pending entries, which disappear as soon as the user decides.
     */
    val suggestedRelays: List<RelayUrl>,
) {
    /**
     * Points the relay permission list at whoever is signed in.
     *
     * Called on sign-in, on restore and on sign-out — the last with null, which
     * leaves the session with no permissions at all until it is given some.
     * The bootstrap suggestions are re-seeded every time, because they are what
     * a list with nothing in it has to offer, and [RelayDirectory.note] skips
     * anything this account has already allowed or blocked.
     */
    suspend fun scopeRelaysTo(account: PubKey?) {
        relayDirectory.scopeTo(account)
        relayDirectory.suggest(suggestedRelays)
    }

    companion object {
        /**
         * Relays offered as a starting point on a fresh install.
         *
         * These are *suggestions* only: they land in the pending queue like any
         * other discovered relay and the app will not open a socket to any of
         * them until the user approves it. Shipping zero would leave a new user
         * with no way to find anything; auto-approving them would make the
         * permission system a lie.
         */
        val BOOTSTRAP_SUGGESTIONS =
            listOf(
                "wss://relay.damus.io",
                "wss://nos.lol",
                "wss://relay.nostr.band",
                "wss://purplepag.es",
                "wss://relay.primal.net",
            )

        suspend fun create(
            backend: NostrBackend,
            settings: KeyValueStore,
            secrets: SecretStore,
            outboxConfig: OutboxConfig = OutboxConfig(),
            bootstrapSuggestions: List<String> = BOOTSTRAP_SUGGESTIONS,
        ): Wayfarer {
            // Empty until somebody is signed in: the list belongs to an
            // account rather than to the phone, so there is nothing to load
            // before it is known whose session this is. See RelayDirectory.scopeTo.
            val directoryStore = PersistedRelayDirectoryStore(settings, RelayDirectoryCodec(backend.normalizer))
            val directory =
                RelayDirectory(
                    clock = backend.clock,
                    persistence = directoryStore,
                )

            // No `suggest` call to match the one below: the media queue starts
            // empty and fills from the profiles the user actually opens. See
            // MediaDirectory's own comment for why nothing is shipped here.
            val mediaStore = PersistedMediaDirectoryStore(settings, MediaDirectoryCodec())
            val media =
                MediaDirectory(
                    clock = backend.clock,
                    initial = mediaStore.load(),
                    persistence = mediaStore,
                )
            val suggested = bootstrapSuggestions.mapNotNull(backend.normalizer::normalize)
            directory.suggest(suggested)

            val transport = backend.transportFactory(directory)
            val relayListCache = RelayListCache()
            // The relay screen's "why Wayfarer wants this" lines name people, and
            // a person is an npub — never the hex the model abbreviates to.
            val describe: (app.wayfarer.core.model.PubKey) -> String = { key ->
                backend.bech32.encodeNpub(key).let { npub -> npub.take(12) + "…" + npub.takeLast(6) }
            }
            val router = OutboxRouter(relayListCache, directory, outboxConfig, describe)

            val relayListRepo =
                RelayListRepository(
                    transport = transport,
                    codec = backend.codec,
                    cache = relayListCache,
                    router = router,
                    directory = directory,
                    clock = backend.clock,
                    describe = describe,
                )

            val eventStore = EventStore()
            val contactRepo = ContactRepository(transport, backend.codec, router, relayListRepo, backend.clock)
            val localFollowStore = LocalFollowStore(settings)
            val articleRepo = ArticleRepository(transport, backend.codec, router, relayListRepo, backend.clock, eventStore)
            val hintQueue = RelayHintQueue()

            return Wayfarer(
                relayDirectory = directory,
                mediaDirectory = media,
                relayLists = relayListCache,
                router = router,
                transport = transport,
                accounts =
                    AccountManager(
                        keyTool = backend.keyTool,
                        bech32 = backend.bech32,
                        secrets = secrets,
                        settings = settings,
                        signerFactory = backend.signerFactory,
                    ),
                profiles = ProfileRepository(transport, backend.codec, router, relayListRepo, backend.clock),
                payments = PaymentRepository(transport, backend.codec, router, relayListRepo, backend.clock),
                feed =
                    FeedRepository(
                        transport, backend.codec, router, relayListRepo, backend.clock,
                        describe, articleRepo, hintQueue, eventStore,
                    ),
                articles = articleRepo,
                threads = ThreadRepository(transport, backend.codec, router, backend.clock, hintQueue, describe, eventStore),
                relayInfo = RelayInfoService(backend.relayInfoFetcher),
                contacts = contactRepo,
                relayListRepo = relayListRepo,
                normalizer = backend.normalizer,
                bech32 = backend.bech32,
                codec = backend.codec,
                onboarding = OnboardingStore(settings),
                preferences = PreferencesStore(settings).also { it.load() },
                relayHints = hintQueue,
                events = eventStore,
                localFollows = localFollowStore,
                follows = FollowBook(contactRepo, localFollowStore),
                clock = backend.clock,
                suggestedRelays = suggested,
            )
        }
    }

    /** Adds a relay the user typed in, already approved for the given directions. */
    suspend fun addRelay(
        raw: String,
        read: Boolean,
        write: Boolean,
    ): RelayUrl? {
        val url = normalizer.normalize(raw) ?: return null
        relayDirectory.approve(url, read, write)
        return url
    }
}
