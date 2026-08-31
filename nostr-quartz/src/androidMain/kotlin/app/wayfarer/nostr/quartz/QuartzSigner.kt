package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.repo.SignerFactory
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal

/**
 * Signs events with a locally held key, via Quartz's `NostrSignerInternal`.
 *
 * Quartz also ships NIP-46 (remote bunker) and NIP-55 (external Android signer)
 * implementations behind the same `NostrSigner` base class. Adding either is a
 * second implementation of [EventSigner] here, with nothing above this file
 * changing — which is the reason [EventSigner] exists at all.
 */
class QuartzLocalSigner(
    pubKey: PubKey,
    secKeyHex: String,
) : EventSigner {
    private val delegate = NostrSignerInternal(KeyPair(privKey = secKeyHex.hexToByteArray()))

    override val pubKeyHex: String = pubKey.hex

    override val canSign: Boolean = true

    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent {
        val signed =
            delegate.sign<Event>(
                createdAt = unsigned.createdAt,
                kind = unsigned.kind,
                tags = unsigned.tags.map { it.toTypedArray() }.toTypedArray(),
                content = unsigned.content,
            )
        return QuartzEventMapping.toCore(signed)
            ?: error("Quartz produced an event this app cannot represent (kind ${unsigned.kind})")
    }
}

/** A watch-only account: everything is readable, nothing can be published. */
class WatchOnlySigner(
    pubKey: PubKey,
) : EventSigner {
    override val pubKeyHex: String = pubKey.hex

    override val canSign: Boolean = false

    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent =
        throw IllegalStateException("This account was added with an npub and holds no secret key")
}

val quartzSignerFactory =
    SignerFactory { pubKey, secKeyHex ->
        if (secKeyHex != null) QuartzLocalSigner(pubKey, secKeyHex) else WatchOnlySigner(pubKey)
    }
