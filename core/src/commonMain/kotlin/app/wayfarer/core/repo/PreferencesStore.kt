package app.wayfarer.core.repo

import app.wayfarer.core.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How many days of silence make somebody "quiet", unless the user says otherwise. */
const val DEFAULT_ACTIVITY_WINDOW_DAYS = 7

/** The choices the settings screen offers. Presets, because this is a coarse filter. */
val ACTIVITY_WINDOW_CHOICES = listOf(1, 3, 7, 14, 30)

/**
 * Settings the user can change, in the shape of [OnboardingStore].
 *
 * One difference from its neighbour: these values are read inside a `combine`
 * that recomputes the browsing screen, so they are held as a [StateFlow] loaded
 * once at startup rather than re-read from storage on every access.
 */
class PreferencesStore(
    private val settings: KeyValueStore,
) {
    private val activityWindow = MutableStateFlow(DEFAULT_ACTIVITY_WINDOW_DAYS)

    /**
     * How recently somebody must have posted to count as active, in days.
     *
     * Drives the Global screen's activity filter. It is a preference rather than
     * a constant because "recently" means something different to somebody
     * following six people than to somebody following six hundred.
     */
    val activityWindowDays: StateFlow<Int> = activityWindow.asStateFlow()

    suspend fun load() {
        // A stored value that is missing, unparseable or nonsensical falls back
        // to the default rather than to zero days, which would report every
        // single person as quiet.
        activityWindow.value = settings.getString(KEY)?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_ACTIVITY_WINDOW_DAYS
    }

    suspend fun setActivityWindowDays(days: Int) {
        if (days <= 0) return
        activityWindow.value = days
        settings.putString(KEY, days.toString())
    }

    private companion object {
        const val KEY = "browse.activity.window.days"
    }
}
