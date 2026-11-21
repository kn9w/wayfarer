package app.wayfarer.core.nostr

import app.wayfarer.core.model.PubKey

/**
 * Finds the people a note mentions, so they can be `p`-tagged and — the part
 * that matters for outbox — so the note is also published to *their* read
 * relays.
 *
 * Scans for NIP-21 `nostr:` URIs and for bare `npub1…` / `nprofile1…`, which is
 * what people actually paste. Decoding is delegated to [Bech32Codec]; anything
 * that fails to decode is simply not a mention.
 */
object Mentions {
    private val candidate = Regex("""(?:nostr:)?(npub1[023456789acdefghjklmnpqrstuvwxyz]{58}|nprofile1[023456789acdefghjklmnpqrstuvwxyz]{20,})""")

    fun extract(
        content: String,
        bech32: Bech32Codec,
    ): Set<PubKey> =
        candidate
            .findAll(content)
            .mapNotNullTo(mutableSetOf()) { bech32.decodePubKey(it.groupValues[1]) }
}
