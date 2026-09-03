package app.wayfarer.core

import app.wayfarer.core.model.Article
import app.wayfarer.core.model.ArticleDraft
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.ProfileDraft
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.PublishOutcome
import app.wayfarer.core.nostr.ReceivedEvent
import app.wayfarer.core.nostr.RelayListEntry
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.nostr.ReqFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription

/**
 * A transport that talks to nobody. Records what it was asked to do, so a test
 * can assert on the *plan* without a relay.
 */
class FakeTransport(
    private val canned: List<ReceivedEvent> = emptyList(),
) : RelayTransport {
    val fetched = mutableListOf<Map<RelayUrl, List<ReqFilter>>>()
    val published = mutableListOf<Pair<NostrEvent, Set<RelayUrl>>>()

    /** Every REQ opened through [subscribe], in order. */
    val subscribed = mutableListOf<Map<RelayUrl, List<ReqFilter>>>()

    /** How many times the client was brought up, and how many times taken down. */
    var startCount = 0
    var stopCount = 0

    /** How many subscriptions are collecting right now. */
    var openSubscriptions = 0
        private set

    /**
     * What a live subscription delivers.
     *
     * A replay buffer, so a test that emits before the collector has attached
     * still delivers, and spare capacity so emitting never suspends the test.
     */
    private val live = MutableSharedFlow<ReceivedEvent>(replay = 8, extraBufferCapacity = 64)

    /** Pushes an event to every open subscription. */
    suspend fun emit(event: ReceivedEvent) {
        live.emit(event)
    }

    /** Writable, so a test can say which relays are connected. */
    val connectedRelays = MutableStateFlow<Set<RelayUrl>>(emptySet())

    override val connected: StateFlow<Set<RelayUrl>> = connectedRelays

    override fun subscribe(plan: Map<RelayUrl, List<ReqFilter>>): Flow<ReceivedEvent> {
        subscribed += plan
        // onSubscription/onCompletion around the shared flow, so a test can assert
        // that cancelling the collector really did close the REQ.
        return live
            .onSubscription { openSubscriptions++ }
            .onCompletion { openSubscriptions-- }
    }

    /**
     * Blocks every fetch until [releaseFetches].
     *
     * Lets a test look at the UI *during* a load. Sampling a flag afterwards
     * cannot do it — StateFlow conflates, so a value raised and cleared inside
     * one load is invisible both to a later read and to a collector.
     */
    fun holdFetches() {
        gate = CompletableDeferred()
    }

    fun releaseFetches() {
        gate?.complete(Unit)
        gate = null
    }

    private var gate: CompletableDeferred<Unit>? = null

    override suspend fun fetch(
        plan: Map<RelayUrl, List<ReqFilter>>,
        idleTimeoutMs: Long,
    ): List<ReceivedEvent> {
        fetched += plan
        gate?.await()
        return canned
    }

    override suspend fun publish(
        event: NostrEvent,
        relays: Set<RelayUrl>,
        timeoutSeconds: Long,
    ): Map<RelayUrl, PublishOutcome> {
        published += event to relays
        return relays.associateWith { PublishOutcome(accepted = true, message = "") }
    }

    override fun start() {
        startCount++
    }

    override fun stop() {
        stopCount++
        connectedRelays.value = emptySet()
    }
}

/**
 * A codec that reads the app's own model straight off the tags, with no nostr
 * library behind it. Enough to exercise repository logic; the real Quartz codec
 * is covered by its own tests against real signatures.
 */
class FakeCodec(
    private val verifies: Boolean = true,
) : NostrCodec {
    /**
     * Name from the content, pictures from tags.
     *
     * Real kind 0 content is JSON, which this module has no parser for. Tags are
     * the one place a fixture can put a picture URL without one, and something
     * has to carry it: the media queue is filled from profiles as they arrive,
     * and a codec that dropped every picture would make that untestable.
     */
    override fun readProfile(event: NostrEvent): Profile? =
        Profile(
            pubKey = event.pubKey,
            name = event.content,
            picture = event.tagRows("picture").firstOrNull()?.getOrNull(1),
            banner = event.tagRows("banner").firstOrNull()?.getOrNull(1),
        )

    override fun writeProfile(
        previous: NostrEvent?,
        draft: ProfileDraft,
        createdAt: Long,
    ) = UnsignedEvent(EventKind.METADATA, draft.name, emptyList(), createdAt)

    override fun readRelayList(event: NostrEvent): List<RelayListEntry> =
        event.tagRows("r").mapNotNull { row ->
            val url = row.getOrNull(1) ?: return@mapNotNull null
            val marker = row.getOrNull(2)
            RelayListEntry(RelayUrl(url), read = marker != "write", write = marker != "read")
        }

    override fun writeRelayList(
        entries: List<RelayListEntry>,
        createdAt: Long,
    ) = UnsignedEvent(EventKind.RELAY_LIST, "", entries.map { listOf("r", it.url.url) }, createdAt)

    override fun readArticle(event: NostrEvent): Article? {
        val dTag = event.tagValues("d").firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val title = event.tagValues("title").firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Article(
            id = event.id,
            author = event.pubKey,
            dTag = dTag,
            title = title,
            summary = event.tagValues("summary").firstOrNull(),
            image = event.tagValues("image").firstOrNull(),
            publishedAt = event.tagValues("published_at").firstOrNull()?.toLongOrNull() ?: event.createdAt,
            content = event.content,
            createdAt = event.createdAt,
            topics = event.tagValues("t"),
        )
    }

    override fun writeArticle(
        draft: ArticleDraft,
        createdAt: Long,
    ) = UnsignedEvent(
        kind = EventKind.LONG_FORM,
        content = draft.content,
        tags =
            listOf(
                listOf("d", draft.dTag),
                listOf("title", draft.title),
                listOf("published_at", (draft.publishedAt.takeIf { it > 0 } ?: createdAt).toString()),
            ) + draft.topics.map { listOf("t", it) },
        createdAt = createdAt,
    )

    override fun readFollows(event: NostrEvent): Set<PubKey> = event.mentionedPubKeys()

    override fun encodeForSigning(
        unsigned: UnsignedEvent,
        authorPubKeyHex: String,
    ): String = """{"kind":${unsigned.kind},"pubkey":"$authorPubKeyHex"}"""

    override fun decodeEvent(json: String): NostrEvent? = null

    /**
     * Enough of the real shape to assert against — the id, kind and content are
     * what a test cares about, and the real escaping belongs to Quartz.
     */
    override fun encodeEvent(event: NostrEvent): String =
        """{"id":"${event.id.hex}","kind":${event.kind},"content":"${event.content}"}"""

    override fun verify(event: NostrEvent): Boolean = verifies
}

/** A signer that stamps events without any crypto. */
class FakeSigner(
    private val author: PubKey,
    override val canSign: Boolean = true,
) : app.wayfarer.core.nostr.EventSigner {
    override val pubKeyHex: String = author.hex

    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent =
        NostrEvent(
            id = EventId("11".repeat(32)),
            pubKey = author,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = "0".repeat(128),
        )
}

/** Builds a text note (kind 1). */
fun noteEvent(
    author: PubKey,
    content: String,
    createdAt: Long,
    idSeed: Int = createdAt.toInt(),
    tags: List<List<String>> = emptyList(),
) = NostrEvent(
    id = EventId(idSeed.toString(16).padStart(2, '0').repeat(32).take(64)),
    pubKey = author,
    createdAt = createdAt,
    kind = EventKind.TEXT_NOTE,
    tags = tags,
    content = content,
    sig = "0".repeat(128),
)

/** Builds a kind 3 contact list naming [follows], the way [FakeCodec] reads it. */
fun contactEvent(
    author: PubKey,
    follows: Collection<PubKey>,
    createdAt: Long = 1,
) = NostrEvent(
    id = EventId("c".repeat(64)),
    pubKey = author,
    createdAt = createdAt,
    kind = EventKind.CONTACT_LIST,
    tags = follows.map { listOf("p", it.hex) },
    content = "",
    sig = "0".repeat(128),
)

/** Builds a kind 0 the way [FakeCodec] reads it: name in content, pictures in tags. */
fun profileEvent(
    author: PubKey,
    name: String,
    picture: String? = null,
    banner: String? = null,
    createdAt: Long = 1,
) = NostrEvent(
    id = EventId("f".repeat(64)),
    pubKey = author,
    createdAt = createdAt,
    kind = EventKind.METADATA,
    tags =
        listOfNotNull(
            picture?.let { listOf("picture", it) },
            banner?.let { listOf("banner", it) },
        ),
    content = name,
    sig = "0".repeat(128),
)

/** Builds a NIP-A3 kind 10133 carrying [targets] as `payto` tags. */
fun paymentEvent(
    author: PubKey,
    vararg targets: Pair<String, String>,
    createdAt: Long = 1,
) = NostrEvent(
    id = EventId("a3".repeat(32)),
    pubKey = author,
    createdAt = createdAt,
    kind = EventKind.PAYMENT_TARGETS,
    tags = targets.map { (type, address) -> listOf("payto", type, address) },
    content = "",
    sig = "0".repeat(128),
)

/** Builds a long-form event the way [FakeCodec] reads it. */
fun articleEvent(
    author: PubKey,
    dTag: String,
    title: String,
    createdAt: Long,
    idSeed: Int = createdAt.toInt(),
    publishedAt: Long? = null,
    topics: List<String> = emptyList(),
    content: String = "body of $title",
) = NostrEvent(
    id = EventId(idSeed.toString(16).padStart(2, '0').repeat(32).take(64)),
    pubKey = author,
    createdAt = createdAt,
    kind = EventKind.LONG_FORM,
    tags =
        listOf(listOf("d", dTag), listOf("title", title)) +
            listOfNotNull(publishedAt?.let { listOf("published_at", it.toString()) }) +
            topics.map { listOf("t", it) },
    content = content,
    sig = "0".repeat(128),
)

/** In-memory settings. */
class FakeKeyValueStore : app.wayfarer.core.store.KeyValueStore {
    val values = mutableMapOf<String, String>()

    /** How many times anything has been written, so a no-op write is visible. */
    var writes = 0
        private set

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        writes++
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

/** In-memory secret storage. */
class FakeSecretStore : app.wayfarer.core.store.SecretStore {
    var secKeyHex: String? = null

    override suspend fun readSecKeyHex(): String? = secKeyHex

    override suspend fun writeSecKeyHex(secKeyHex: String) {
        this.secKeyHex = secKeyHex
    }

    override suspend fun clear() {
        secKeyHex = null
    }
}

/** Deterministic keys: no secp256k1, just a reversible mapping good enough for wiring tests. */
class FakeKeyTool(
    private val counter: () -> Int,
) : app.wayfarer.core.nostr.KeyTool {
    override fun generateSecKeyHex(): String = counter().toString(16).padStart(2, '0').repeat(32).take(64)

    override fun pubKeyOf(secKeyHex: String): PubKey = PubKey(secKeyHex)
}

/** `npub…`/`nsec…` are just prefixed hex here; the real codec is tested against Quartz. */
object FakeBech32Codec : app.wayfarer.core.nostr.Bech32Codec {
    private const val HEX = "0123456789abcdef"

    /** Bech32's own alphabet, as far as 16 symbols. */
    private const val BECH = "qpzry9x8gf2tvdw0"

    /**
     * Substituted rather than concatenated.
     *
     * `"npub" + hex` would have made this fake useless for the one property
     * worth asserting about npubs — that the raw hex key never reaches the
     * screen — because every encoded npub would still literally contain it.
     */
    private fun toBech(hex: String) = hex.map { BECH[HEX.indexOf(it)] }.joinToString("")

    private fun fromBech(text: String): String? =
        buildString {
            for (symbol in text) {
                val index = BECH.indexOf(symbol)
                if (index < 0) return null
                append(HEX[index])
            }
        }

    override fun encodeNpub(pubKey: PubKey) = "npub" + toBech(pubKey.hex)

    override fun encodeNsec(secKeyHex: String) = "nsec$secKeyHex"

    override fun encodeNote(eventIdHex: String) = "note$eventIdHex"

    override fun decodePubKey(input: String): PubKey? {
        val body = input.removePrefix("npub")
        // Bare hex too: real signers hand back either, and MainActivity relies on it.
        return PubKey.parseOrNull(fromBech(body)) ?: PubKey.parseOrNull(body)
    }

    /** `nprofile<hex>@relay,relay` stands in for the real TLV encoding. */
    override fun decodeProfileRef(input: String): app.wayfarer.core.nostr.ProfileRef? {
        val cleaned = input.trim().removePrefix("nostr:")
        if (cleaned.startsWith("nprofile")) {
            val body = cleaned.removePrefix("nprofile")
            val encoded = body.substringBefore('@')
            val pubKey = PubKey.parseOrNull(fromBech(encoded)) ?: PubKey.parseOrNull(encoded) ?: return null
            val hints = body.substringAfter('@', "").split(',').filter { it.isNotBlank() }
            return app.wayfarer.core.nostr.ProfileRef(pubKey, hints)
        }
        return decodePubKey(cleaned)?.let { app.wayfarer.core.nostr.ProfileRef(it) }
    }

    override fun decodeSecKeyHex(input: String) =
        input.removePrefix("nsec").takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
}

/** A NIP-11 fetcher that is never expected to be called. */
object UnusedRelayInfoFetcher : app.wayfarer.core.nostr.RelayInfoFetcher {
    override suspend fun fetch(url: RelayUrl) = throw AssertionError("relay info must not be fetched without a user request")
}
