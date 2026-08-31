package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.nostr.Bech32Codec
import app.wayfarer.core.nostr.KeyTool
import app.wayfarer.core.nostr.RelayUrlNormalizer
import app.wayfarer.core.util.Clock
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer as QuartzRelayUrlNormalizer
import com.vitorpamplona.quartz.nip19Bech32.decodePrivateKeyAsHexOrNull
import com.vitorpamplona.quartz.nip19Bech32.decodePublicKeyAsHexOrNull
import com.vitorpamplona.quartz.nip19Bech32.toNote
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * secp256k1 key generation and derivation, via Quartz's `Nip01Crypto`.
 *
 * Replacing this means providing 32 bytes of cryptographically secure randomness
 * and an x-only pubkey derivation — nothing else in the app touches secp256k1.
 */
class QuartzKeyTool : KeyTool {
    override fun generateSecKeyHex(): String = Nip01Crypto.privKeyCreate().toHexKey()

    override fun pubKeyOf(secKeyHex: String): PubKey = PubKey(Nip01Crypto.pubKeyCreate(secKeyHex.hexToByteArray()).toHexKey())
}

/**
 * NIP-19 bech32 entities, via Quartz.
 *
 * [decodePubKey] accepts `npub`, `nprofile` and bare hex; [decodeSecKeyHex]
 * accepts `nsec` and bare hex. Quartz's decoders return null rather than
 * throwing on malformed input, which is what the login screen wants.
 */
class QuartzBech32Codec : Bech32Codec {
    override fun encodeNpub(pubKey: PubKey): String = pubKey.hex.hexToByteArray().toNpub()

    override fun encodeNsec(secKeyHex: String): String = secKeyHex.hexToByteArray().toNsec()

    @Suppress("DEPRECATION")
    override fun encodeNote(eventIdHex: String): String = eventIdHex.hexToByteArray().toNote()

    override fun decodePubKey(input: String): PubKey? {
        val cleaned = input.trim().removePrefix("nostr:")
        // Quartz's decoder falls back to `Hex.decode` for non-bech32 input, which
        // happily accepts any even-length hex string. Re-check the length here so
        // a 33-byte compressed key or a truncated paste cannot become a PubKey.
        return PubKey.parseOrNull(decodePublicKeyAsHexOrNull(cleaned))
    }

    override fun decodeSecKeyHex(input: String): String? {
        val cleaned = input.trim().removePrefix("nostr:")
        val hex = decodePrivateKeyAsHexOrNull(cleaned)?.lowercase() ?: return null
        return if (hex.length == 64 && hex.all { it in "0123456789abcdef" }) hex else null
    }
}

/**
 * Relay URL normalization, via Quartz's `RelayUrlNormalizer`.
 *
 * This one matters more than it looks: [app.wayfarer.core.model.RelayUrl] equality
 * is what makes the permission map trustworthy, so `wss://Relay.example` and
 * `wss://relay.example/` must collapse to the same key or a user could approve
 * one spelling and unknowingly connect to the other.
 */
val quartzRelayUrlNormalizer =
    RelayUrlNormalizer { raw ->
        QuartzRelayUrlNormalizer.normalizeOrNull(raw)?.let { app.wayfarer.core.model.RelayUrl(it.url) }
    }

/** Wall clock, via Quartz's `TimeUtils`. Trivially replaceable. */
val quartzClock = Clock { TimeUtils.now() }
