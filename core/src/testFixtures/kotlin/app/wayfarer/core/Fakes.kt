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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A transport that talks to nobody. Records what it was asked to do, so a test
 * can assert on the *plan* without a relay.
 */
class FakeTransport(
    private val canned: List<ReceivedEvent> = emptyList(),
) : RelayTransport {
    val fetched = mutableListOf<Map<RelayUrl, List<ReqFilter>>>()
    val published = mutableListOf<Pair<NostrEvent, Set<RelayUrl>>>()

    /** How many times the client was brought up. Should be at most one per session. */
    var startCount = 0

    override val connected: StateFlow<Set<RelayUrl>> = MutableStateFlow(emptySet())

    override fun subscribe(plan: Map<RelayUrl, List<ReqFilter>>): Flow<ReceivedEvent> = emptyFlow()

    override suspend fun fetch(
        plan: Map<RelayUrl, List<ReqFilter>>,
        idleTimeoutMs: Long,
    ): List<ReceivedEvent> {
        fetched += plan
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

    override fun stop() = Unit
}

/**
 * A codec that reads the app's own model straight off the tags, with no nostr
 * library behind it. Enough to exercise repository logic; the real Quartz codec
 * is covered by its own tests against real signatures.
 */
class FakeCodec(
    private val verifies: Boolean = true,
) : NostrCodec {
    override fun readProfile(event: NostrEvent): Profile? = Profile(event.pubKey, name = event.content)

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
        )
    }

    override fun writeArticle(
        draft: ArticleDraft,
        createdAt: Long,
    ) = UnsignedEvent(
        kind = EventKind.LONG_FORM,
        content = draft.content,
        tags = listOf(listOf("d", draft.dTag), listOf("title", draft.title)),
        createdAt = createdAt,
    )

    override fun readFollows(event: NostrEvent): Set<PubKey> = event.mentionedPubKeys()

    override fun encodeForSigning(
        unsigned: UnsignedEvent,
        authorPubKeyHex: String,
    ): String = """{"kind":${unsigned.kind},"pubkey":"$authorPubKeyHex"}"""

    override fun decodeEvent(json: String): NostrEvent? = null

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

/** Builds a long-form event the way [FakeCodec] reads it. */
fun articleEvent(
    author: PubKey,
    dTag: String,
    title: String,
    createdAt: Long,
    idSeed: Int = createdAt.toInt(),
) = NostrEvent(
    id = EventId(idSeed.toString(16).padStart(2, '0').repeat(32).take(64)),
    pubKey = author,
    createdAt = createdAt,
    kind = EventKind.LONG_FORM,
    tags = listOf(listOf("d", dTag), listOf("title", title)),
    content = "body of $title",
    sig = "0".repeat(128),
)

/** In-memory settings. */
class FakeKeyValueStore : app.wayfarer.core.store.KeyValueStore {
    val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(
        key: String,
        value: String,
    ) {
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
    override fun encodeNpub(pubKey: PubKey) = "npub${pubKey.hex}"

    override fun encodeNsec(secKeyHex: String) = "nsec$secKeyHex"

    override fun encodeNote(eventIdHex: String) = "note$eventIdHex"

    override fun decodePubKey(input: String) = PubKey.parseOrNull(input.removePrefix("npub"))

    /** `nprofile<hex>@relay,relay` stands in for the real TLV encoding. */
    override fun decodeProfileRef(input: String): app.wayfarer.core.nostr.ProfileRef? {
        val cleaned = input.trim().removePrefix("nostr:")
        if (cleaned.startsWith("nprofile")) {
            val body = cleaned.removePrefix("nprofile")
            val pubKey = PubKey.parseOrNull(body.substringBefore('@')) ?: return null
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
