package app.wayfarer.core.nostr

import app.wayfarer.core.model.Article
import app.wayfarer.core.model.ArticleDraft
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.ProfileDraft
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.UnsignedEvent

/**
 * Everything the core needs to *interpret* nostr data, expressed without naming
 * any library. The `nostr-quartz` module is the only implementation.
 *
 * The split is by concern rather than by NIP so that a replacement backend has a
 * short, mechanical checklist: five small interfaces, no inheritance, no types
 * of ours leaking in.
 */
interface NostrCodec {
    /** kind 0 content JSON -> [Profile]. Returns null if the content is unusable. */
    fun readProfile(event: NostrEvent): Profile?

    /**
     * Builds an unsigned kind 0 from [draft], merged over [previous] so that
     * fields this app does not model — set by some other client — survive the
     * edit instead of being wiped.
     */
    fun writeProfile(
        previous: NostrEvent?,
        draft: ProfileDraft,
        createdAt: Long,
    ): UnsignedEvent

    /** kind 10002 -> the author's advertised read/write relays (NIP-65). */
    fun readRelayList(event: NostrEvent): List<RelayListEntry>

    /** An unsigned kind 10002 advertising [entries]. */
    fun writeRelayList(
        entries: List<RelayListEntry>,
        createdAt: Long,
    ): UnsignedEvent

    /** kind 30023 -> a long-form article (NIP-23). Null if it carries no title or d tag. */
    fun readArticle(event: NostrEvent): Article?

    /**
     * An unsigned kind 30023 from [draft].
     *
     * [ArticleDraft.dTag] must be carried over when editing: kind 30023 is
     * addressable, so a changed d tag publishes a second article rather than
     * replacing the first.
     */
    fun writeArticle(
        draft: ArticleDraft,
        createdAt: Long,
    ): UnsignedEvent

    /** kind 3 -> the pubkeys the author follows (NIP-02). */
    fun readFollows(event: NostrEvent): Set<PubKey>

    /**
     * Serializes an unsigned event as the JSON an external signer expects.
     *
     * Lives here rather than in the Android layer so the app needs no JSON
     * library of its own, and so the escaping is done by the same code that
     * parses events off the wire.
     */
    fun encodeForSigning(
        unsigned: UnsignedEvent,
        authorPubKeyHex: String,
    ): String

    /** Parses a signed event handed back by an external signer. Null if unusable. */
    fun decodeEvent(json: String): NostrEvent?

    /** Verifies id and signature. Events failing this are dropped on arrival. */
    fun verify(event: NostrEvent): Boolean
}

/** One `r` tag of a NIP-65 relay list. Both flags set means an unmarked tag. */
data class RelayListEntry(
    val url: RelayUrl,
    val read: Boolean,
    val write: Boolean,
)

/** NIP-19 bech32 entities, in and out. */
interface Bech32Codec {
    fun encodeNpub(pubKey: PubKey): String

    fun encodeNsec(secKeyHex: String): String

    fun encodeNote(eventIdHex: String): String

    /** Accepts npub / nprofile / raw hex. Null if none of those. */
    fun decodePubKey(input: String): PubKey?

    /** Accepts nsec / raw hex. Null if neither. Returned as lowercase hex. */
    fun decodeSecKeyHex(input: String): String?
}

/** secp256k1 key handling. */
interface KeyTool {
    /** A fresh random secret key, lowercase hex. */
    fun generateSecKeyHex(): String

    /** x-only public key for a secret key, lowercase hex. */
    fun pubKeyOf(secKeyHex: String): PubKey
}

/** Relay URL normalization — the function that makes [RelayUrl] equality meaningful. */
fun interface RelayUrlNormalizer {
    /** Null when [raw] is not a usable relay address. */
    fun normalize(raw: String): RelayUrl?
}
