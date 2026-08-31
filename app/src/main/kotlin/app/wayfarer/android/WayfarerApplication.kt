package app.wayfarer.android

import android.app.Application
import app.wayfarer.android.platform.AndroidKeyValueStore
import app.wayfarer.android.platform.AndroidSecretStore
import app.wayfarer.core.Wayfarer
import app.wayfarer.nostr.quartz.quartzBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Composition root. Three lines of wiring and one `await`.
 *
 * This is the entire coupling between the app and its nostr backend: swapping
 * Quartz out means replacing [quartzBackend] here, and nothing else in the
 * project changes.
 */
class WayfarerApplication : Application() {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Started eagerly at process start (it reads the relay directory off disk)
     * and awaited by the first screen that needs it.
     */
    private val deferred by lazy {
        scope.async {
            Wayfarer.create(
                backend = quartzBackend(scope),
                settings = AndroidKeyValueStore(this@WayfarerApplication),
                secrets = AndroidSecretStore(this@WayfarerApplication),
            )
        }
    }

    suspend fun wayfarer(): Wayfarer = deferred.await()
}
