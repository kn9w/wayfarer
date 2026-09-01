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
     * Returns [DeviceAuthOutcome.UNAVAILABLE] when there is no screen lock to ask
     * with — which is a fact about the device the user is told, not a refusal.
     * Refusing outright would lock somebody out of their own key because they
     * never set a PIN.
     */
    suspend fun confirm(
        title: String,
        description: String,
    ): DeviceAuthOutcome {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard == null || !keyguard.isDeviceSecure) return DeviceAuthOutcome.UNAVAILABLE

        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent(title, description) ?: return DeviceAuthOutcome.UNAVAILABLE

        return lock.withLock {
            val slot = CompletableDeferred<Boolean>()
            pending = slot
            try {
                launcher.launch(intent)
                if (slot.await()) DeviceAuthOutcome.CONFIRMED else DeviceAuthOutcome.REJECTED
            } catch (failure: Throwable) {
                // Nothing could handle the intent. Treated as "could not ask" rather
                // than "was refused", so the caller can say which happened.
                DeviceAuthOutcome.UNAVAILABLE
            } finally {
                pending = null
            }
        }
    }
}
