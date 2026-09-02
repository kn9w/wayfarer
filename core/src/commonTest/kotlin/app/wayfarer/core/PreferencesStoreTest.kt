package app.wayfarer.core

import app.wayfarer.core.repo.DEFAULT_ACTIVITY_WINDOW_DAYS
import app.wayfarer.core.repo.PreferencesStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferencesStoreTest {
    @Test
    fun `an untouched install gets the default window`() =
        runTest {
            val prefs = PreferencesStore(FakeKeyValueStore())

            prefs.load()

            assertEquals(7, DEFAULT_ACTIVITY_WINDOW_DAYS)
            assertEquals(DEFAULT_ACTIVITY_WINDOW_DAYS, prefs.activityWindowDays.value)
        }

    @Test
    fun `a chosen window is remembered across a restart`() =
        runTest {
            val settings = FakeKeyValueStore()
            PreferencesStore(settings).setActivityWindowDays(30)

            val restarted = PreferencesStore(settings)
            restarted.load()

            assertEquals(30, restarted.activityWindowDays.value)
        }

    @Test
    fun `a nonsense stored value falls back rather than hiding everyone`() =
        runTest {
            val settings = FakeKeyValueStore()

            for (junk in listOf("", "soon", "-3", "0")) {
                settings.values["browse.activity.window.days"] = junk
                val prefs = PreferencesStore(settings)
                prefs.load()

                // Zero days would report every single person as quiet, which
                // looks exactly like the app having lost the user's follows.
                assertEquals(DEFAULT_ACTIVITY_WINDOW_DAYS, prefs.activityWindowDays.value, "for stored value \"$junk\"")
            }
        }

    @Test
    fun `a window of zero days is refused rather than stored`() =
        runTest {
            val settings = FakeKeyValueStore()
            val prefs = PreferencesStore(settings)
            prefs.load()

            prefs.setActivityWindowDays(0)

            assertEquals(DEFAULT_ACTIVITY_WINDOW_DAYS, prefs.activityWindowDays.value)
        }
}
