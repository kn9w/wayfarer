package app.wayfarer.core.relay

import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayInfo
import app.wayfarer.core.nostr.RelayInfoFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NIP-11 relay information, fetched only when the user explicitly asks for it.
 *
 * Reading a relay's information document means making an HTTPS request to that
 * relay — including, usefully, to relays the user has not approved, since
 * "what does this relay say it is?" is exactly the question worth answering
 * *before* approving it. That is a real connection either way, so it is not
 * something this app does on its own initiative.
 *
 * Hence the method name: [fetchOnUserRequest] is the only entry point, it is
 * called from exactly one place (the "Fetch relay info" action), and for an
 * unapproved relay the UI confirms the host first. Nothing here is wired to a
 * background refresh, and adding one would be a change of behaviour that has to
 * be argued for rather than a tidy-up.
 */
class RelayInfoService(
    private val fetcher: RelayInfoFetcher,
) {
    /** What is known about a relay, for the settings screen. */
    sealed interface Entry {
        data object Loading : Entry

        data class Loaded(
            val info: RelayInfo,
        ) : Entry

        data class Failed(
            val message: String,
        ) : Entry
    }

    private val state = MutableStateFlow<Map<RelayUrl, Entry>>(emptyMap())

    val entries: StateFlow<Map<RelayUrl, Entry>> = state.asStateFlow()

    operator fun get(url: RelayUrl): Entry? = state.value[url]

    /**
     * Fetches [url]'s NIP-11 document because the user asked for it.
     *
     * Cached: a second request for a relay already loaded returns the stored
     * answer without touching the network. Pass `force = true` to re-fetch,
     * which is again a user action.
     */
    suspend fun fetchOnUserRequest(
        url: RelayUrl,
        force: Boolean = false,
    ): Entry {
        val existing = state.value[url]
        if (!force && existing is Entry.Loaded) return existing
        if (existing is Entry.Loading) return existing

        state.value = state.value + (url to Entry.Loading)
        val result =
            try {
                Entry.Loaded(fetcher.fetch(url))
            } catch (failure: Throwable) {
                Entry.Failed(failure.message ?: "Could not read this relay's information document")
            }
        state.value = state.value + (url to result)
        return result
    }

    /** Drops what is known about [url] — used when a relay is forgotten. */
    fun clear(url: RelayUrl) {
        state.value = state.value - url
    }
}
