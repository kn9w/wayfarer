package app.wayfarer.core

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
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.relay.RelayInfoService
import app.wayfarer.core.repo.AccountManager
import app.wayfarer.core.repo.ArticleRepository
import app.wayfarer.core.repo.ContactRepository
import app.wayfarer.core.repo.FeedRepository
import app.wayfarer.core.repo.OnboardingStore
import app.wayfarer.core.repo.ProfileRepository
import app.wayfarer.core.repo.RelayListRepository
import app.wayfarer.core.repo.ThreadRepository
import app.wayfarer.core.repo.SignerFactory
import app.wayfarer.core.store.KeyValueStore
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
 * Construct via [Wayfarer.create], which loads persisted relay permissions and
 * seeds the pending queue with the bootstrap suggestions.
 */
class Wayfarer private constructor(
    val relayDirectory: RelayDirectory,
    val relayLists: RelayListCache,
    val router: OutboxRouter,
    val transport: RelayTransport,
    val accounts: AccountManager,
    val profiles: ProfileRepository,
    val feed: FeedRepository,
    val articles: ArticleRepository,
    /** Conversations under a note or an article. Fetched on demand, not streamed. */
    val threads: ThreadRepository,
    val relayInfo: RelayInfoService,
    val contacts: ContactRepository,
    val relayListRepo: RelayListRepository,
    val normalizer: RelayUrlNormalizer,
    val bech32: Bech32Codec,
    val onboarding: OnboardingStore,
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
            val directoryStore = PersistedRelayDirectoryStore(settings, RelayDirectoryCodec(backend.normalizer))
            val directory =
                RelayDirectory(
                    clock = backend.clock,
                    initial = directoryStore.load(),
                    persistence = directoryStore,
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

            val articleRepo = ArticleRepository(transport, backend.codec, router, relayListRepo, backend.clock)

            return Wayfarer(
                relayDirectory = directory,
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
                feed = FeedRepository(transport, backend.codec, router, relayListRepo, backend.clock, articleRepo),
                articles = articleRepo,
                threads = ThreadRepository(transport, backend.codec, router, backend.clock),
                relayInfo = RelayInfoService(backend.relayInfoFetcher),
                contacts = ContactRepository(transport, backend.codec, router, relayListRepo),
                relayListRepo = relayListRepo,
                normalizer = backend.normalizer,
                bech32 = backend.bech32,
                onboarding = OnboardingStore(settings),
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
