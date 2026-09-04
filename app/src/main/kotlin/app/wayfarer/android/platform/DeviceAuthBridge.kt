package app.wayfarer.android.platform

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import app.wayfarer.android.viewmodel.DeviceAuthOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Asks Android to confirm the person holding the phone is its owner.
 *
 * Used in one place: before the account's secret key is put on screen. The key
 * itself is already encrypted at rest by an `AndroidKeyStore` key, which
 * protects the file; this protects the *screen*, which is the part a keystore
 * cannot help with.
 *
 * `KeyguardManager` rather than `androidx.biometric`: it is the platform's own
 * lock screen — fingerprint, face or PIN, whatever the user set up — and it
 * costs no dependency. The API is deprecated in favour of `BiometricPrompt`,
 * which would be a new library for one call in an app that carries as few as it
 * can. Switching later changes only this file.
 *
 * One request at a time, for the same reason as [Nip55Bridge]: a single launcher
 * and a single pending slot.
 */
class DeviceAuthBridge(
    private val context: Context,
    private val launcher: ActivityResultLauncher<Intent>,
) {
    private val lock = Mutex()

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    /** Called by the activity's result callback. */
    fun onActivityResult(result: ActivityResult) {
        pending?.complete(result.resultCode == Activity.RESULT_OK)
    }

    /**
     * Two ways of not getting an answer, and they are not the same thing.
     *
     * [DeviceAuthOutcome.NO_LOCK_SET] is a fact about the device: there is no
     * screen lock, so there is nothing to ask with. The caller shows the key
     * anyway and names the gap, because refusing would lock somebody out of
     * their own key over a setting they chose.
     *
     * [DeviceAuthOutcome.FAILED] is everything else — the keyguard service
     * missing, the system declining to build the intent, nothing able to handle
     * it. A device that has a lock screen and could not show it has not
     * confirmed anything, and must not be treated as though it had. These two
     * shared a value once, and the effect was that any error revealed the key.
     */
    suspend fun confirm(
        title: String,
        description: String,
    ): DeviceAuthOutcome {
        val keyguard =
            context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                // The service is always present on a real device; its absence is a
                // broken platform, not an unlocked one.
                ?: return DeviceAuthOutcome.FAILED

        if (!keyguard.isDeviceSecure) return DeviceAuthOutcome.NO_LOCK_SET

        @Suppress("DEPRECATION")
        val intent =
            keyguard.createConfirmDeviceCredentialIntent(title, description)
                // isDeviceSecure said there is a credential, so this returning
                // null contradicts the line above rather than describing a phone
                // without a PIN.
                ?: return DeviceAuthOutcome.FAILED

        return lock.withLock {
            val slot = CompletableDeferred<Boolean>()
            pending = slot
            try {
                launcher.launch(intent)
                if (slot.await()) DeviceAuthOutcome.CONFIRMED else DeviceAuthOutcome.REJECTED
            } catch (failure: Throwable) {
                // Nothing could handle the intent. Not a refusal, and not
                // permission either.
                DeviceAuthOutcome.FAILED
            } finally {
                pending = null
            }
        }
    }
}
