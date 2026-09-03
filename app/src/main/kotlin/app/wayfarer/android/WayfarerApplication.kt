package app.wayfarer.android

import android.app.Application
import app.wayfarer.android.platform.AndroidKeyValueStore
import app.wayfarer.android.platform.AndroidSecretStore
import app.wayfarer.android.platform.AppSignerFactory
import app.wayfarer.android.platform.ImageLoader
import app.wayfarer.android.platform.Nip55Bridge
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
     * Set by the activity once it has registered an activity-result launcher, and
     * cleared when it goes away. Held here rather than in the container because a
     * NIP-55 signature outlives no activity: the signer is rebuilt from the stored
     * credential on every launch, but it can only reach the signer app while an
     * activity is alive.
     */
    @Volatile
    var nip55Bridge: Nip55Bridge? = null

    /**
     * Started eagerly at process start (it reads the relay directory off disk)
     * and awaited by the first screen that needs it.
     */
    private val deferred by lazy {
        scope.async {
            val backend = quartzBackend(scope)
            Wayfarer.create(
                backend =
                    backend.copy(
                        signerFactory = AppSignerFactory(backend.signerFactory, backend.codec) { nip55Bridge },
                    ),
                settings = AndroidKeyValueStore(this@WayfarerApplication),
                secrets = AndroidSecretStore(this@WayfarerApplication),
            )
        }
    }

    suspend fun wayfarer(): Wayfarer = deferred.await()

    /**
     * The one client pictures are fetched through, gated on the media directory.
     *
     * Built here rather than in the container because it needs a cache directory
     * and OkHttp, neither of which the core may know about — and built from the
     * directory the container created, so the gate and the screen that fills it
     * are looking at the same list.
     */
    private val deferredImages by lazy {
        scope.async {
            val core = deferred.await()
            ImageLoader(ImageLoader.client(core.mediaDirectory, cacheDir))
        }
    }

    suspend fun imageLoader(): ImageLoader = deferredImages.await()
}
