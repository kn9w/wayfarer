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
        fun isSignerInstalled(context: Context): Boolean {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55Protocol.SCHEME}:"))
            return context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
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
                    ?: throw IllegalStateException("The signer app returned no signed event")
            Nip55Protocol.Reply.Rejected -> throw SigningRejected()
            is Nip55Protocol.Reply.Failed -> throw IllegalStateException(reply.message)
        }
    }
}

/** The user declined the signing request in the signer app. Not an error. */
class SigningRejected : Exception("Signing was declined in the signer app")
