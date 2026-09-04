package app.wayfarer.android.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import app.wayfarer.android.signer.Nip55Protocol
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.NostrCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns the activity-result dance a NIP-55 signer requires into a suspending call.
 *
 * `EventSigner.sign` is a plain suspend function in the core, which knows nothing
 * about activities. This is the adapter: it launches the signer, parks the
 * coroutine, and resumes it when the result arrives.
 *
 * One request at a time — a [Mutex] serialises callers, because there is a single
 * launcher and a single pending slot, and interleaving two signatures would
 * deliver each the other's answer.
 */
class Nip55Bridge(
    private val launcher: ActivityResultLauncher<Intent>,
) {
    private val lock = Mutex()

    @Volatile
    private var pending: CompletableDeferred<Nip55Protocol.Reply>? = null

    /** Called by the activity's result callback. */
    fun onActivityResult(result: ActivityResult) {
        val extras =
            result.data?.extras?.let { bundle ->
                bundle.keySet().associateWith { key ->
                    @Suppress("DEPRECATION")
                    bundle.get(key)?.toString()
                }
            }
        pending?.complete(Nip55Protocol.parseReply(result.resultCode == Activity.RESULT_OK, extras))
    }

    suspend fun send(request: Nip55Protocol.Request): Nip55Protocol.Reply =
        lock.withLock {
            val slot = CompletableDeferred<Nip55Protocol.Reply>()
            pending = slot
            try {
                val intent =
                    Intent(Intent.ACTION_VIEW, Uri.parse(request.uri)).apply {
                        request.packageName?.let { setPackage(it) }
                        for ((key, value) in request.extras) putExtra(key, value)
                    }
                launcher.launch(intent)
                slot.await()
            } catch (failure: Throwable) {
                // Most likely no activity could handle the intent — i.e. the signer
                // was uninstalled between the check and the launch.
                Nip55Protocol.Reply.Failed(failure.message ?: "Could not reach the signer app")
            } finally {
                pending = null
            }
        }

    companion object {
        /**
         * Whether any NIP-55 signer is installed.
         *
         * Requires the `<queries>` declaration for the `nostrsigner` scheme in the
         * manifest; without it this silently returns false on API 30 and above.
         */
        fun isSignerInstalled(context: Context): Boolean = signerName(context) != null

        /**
         * What the installed signer calls itself, or null when there is none.
         *
         * The name is worth having on the sign-in screen: "Log in with Amber" is
         * a thing somebody recognises, where "use a signer app" is a category
         * they may not know they are already in. The first match is taken —
         * NIP-55 has no notion of a preferred signer, and the chooser appears
         * anyway if more than one can handle the intent.
         *
         * Requires the `<queries>` declaration for the `nostrsigner` scheme in
         * the manifest; without it this silently returns null on API 30 and above.
         */
        fun signerName(context: Context): String? {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55Protocol.SCHEME}:"))
            val manager = context.packageManager
            val match = manager.queryIntentActivities(probe, 0).firstOrNull() ?: return null
            return match.loadLabel(manager).toString().takeIf { it.isNotBlank() }
        }
    }
}

/**
 * An [EventSigner] backed by an external signer app. No key material is ever
 * held by this process; every signature is a round trip the user approves.
 */
class Nip55Signer(
    pubKey: PubKey,
    private val signerPackage: String,
    /**
     * Resolved per signature, not captured once.
     *
     * The bridge belongs to an activity's result launcher, so a captured one goes
     * stale the moment the activity is recreated — after a rotation the next
     * signature would be launched through a destroyed activity. Looking it up here
     * also means building this signer never fails, which is what lets a stored
     * NIP-55 session be restored at process start with no activity attached yet.
     */
    private val bridge: () -> Nip55Bridge?,
    private val codec: NostrCodec,
) : EventSigner {
    override val pubKeyHex: String = pubKey.hex

    override val canSign: Boolean = true

    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent {
        val active = bridge() ?: throw IllegalStateException("Open Wayfarer to approve this in your signer app")
        val json = codec.encodeForSigning(unsigned, pubKeyHex)
        val reply =
            active.send(
                Nip55Protocol.signEvent(
                    eventJson = json,
                    currentUserHex = pubKeyHex,
                    signerPackage = signerPackage,
                    // Any stable per-request string works; the signer echoes it back.
                    id = "${unsigned.kind}-${unsigned.createdAt}",
                ),
            )

        return when (reply) {
            is Nip55Protocol.Reply.Ok ->
                reply.event
                    ?.let(codec::decodeEvent)
                    ?.also { checkMatches(it, unsigned) }
                    ?: throw IllegalStateException("The signer app returned no signed event")
            Nip55Protocol.Reply.Rejected -> throw SigningRejected()
            is Nip55Protocol.Reply.Failed -> throw IllegalStateException(reply.message)
        }
    }

    /**
     * Checks that the signer signed what it was asked to sign.
     *
     * A signer holds the key, so this is not a defence against a malicious one
     * getting hold of it — but the trust that has to be extended is narrower
     * than "publish whatever comes back". Any installed app can register the
     * `nostrsigner` scheme and appear in the chooser, and [decodeEvent] only
     * parses: without this, an event that is not the user's, or not the note
     * they wrote, would be published under their identity with nothing to
     * contradict it.
     *
     * Tags are deliberately not compared. Signers legitimately add their own —
     * a client tag is the common case — and rejecting those would break working
     * setups to catch nothing that the pubkey and signature checks miss.
     *
     * `created_at` is compared, because it has no such excuse and it is not
     * cosmetic: for the replaceable kinds this app writes, a far-future
     * timestamp is what decides which copy every other client keeps, so a signer
     * free to move it is a signer free to pin one version of a profile or relay
     * list in place for good.
     */
    private fun checkMatches(
        signed: NostrEvent,
        asked: UnsignedEvent,
    ) {
        if (signed.pubKey.hex != pubKeyHex) {
            throw IllegalStateException("The signer app returned an event signed by a different account")
        }
        if (signed.kind != asked.kind || signed.content != asked.content || signed.createdAt != asked.createdAt) {
            throw IllegalStateException("The signer app returned a different event than the one it was asked to sign")
        }
        if (!codec.verify(signed)) {
            throw IllegalStateException("The signer app returned an event whose signature does not check out")
        }
    }
}

/** The user declined the signing request in the signer app. Not an error. */
class SigningRejected : Exception("Signing was declined in the signer app")
