package app.wayfarer.android.platform

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.repo.Credential
import app.wayfarer.core.repo.SignerFactory

/**
 * Adds the NIP-55 case to whatever the nostr backend can build on its own.
 *
 * External signing needs an Activity, so it cannot live in `nostr-quartz`. This
 * is the seam that lets it live here instead without anything in `core`
 * knowing: the core asks a [SignerFactory] for a signer and gets one.
 */
class AppSignerFactory(
    private val delegate: SignerFactory,
    private val codec: NostrCodec,
    /** Null until the activity has registered its launcher. */
    private val bridge: () -> Nip55Bridge?,
) : SignerFactory {
    override fun create(
        pubKey: PubKey,
        credential: Credential,
    ): EventSigner =
        when (credential) {
            // Never fails here: the bridge is resolved when a signature is actually
            // requested, so restoring a stored NIP-55 session at process start works
            // with no activity attached.
            is Credential.ExternalSigner -> Nip55Signer(pubKey, credential.packageName, bridge, codec)
            else -> delegate.create(pubKey, credential)
        }
}
