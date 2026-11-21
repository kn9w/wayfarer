package app.wayfarer.core.repo

import app.wayfarer.core.store.KeyValueStore

/**
 * Whether the user has been through the introduction.
 *
 * One persisted bit, deliberately independent of whether an account exists: a
 * user who looked at the introduction and decided to carry on without an
 * account has still been introduced, and sending them back to the first screen
 * on every launch would read as the app refusing to let them in.
 */
class OnboardingStore(
    private val settings: KeyValueStore,
) {
    suspend fun isComplete(): Boolean = settings.getString(KEY) == "true"

    suspend fun markComplete() {
        settings.putString(KEY, "true")
    }

    /** Shows the introduction again — used by "start over" in settings. */
    suspend fun reset() {
        settings.remove(KEY)
    }

    private companion object {
        const val KEY = "onboarding.complete"
    }
}
