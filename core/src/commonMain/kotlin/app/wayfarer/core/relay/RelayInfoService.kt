package app.wayfarer.core.relay

import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayInfo
import app.wayfarer.core.nostr.RelayInfoFetcher
import app.wayfarer.core.util.StoreLimits
import app.wayfarer.core.util.plusBounded
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

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

        state.value = state.value.plusBounded(url, Entry.Loading, StoreLimits.RELAY_INFO)
        val result =
            try {
                // A relay that accepts the connection and then says nothing must
                // not be able to hold this entry on [Entry.Loading] for the rest
                // of the session — the guard above would then refuse every retry,
                // and the button that reads this relay's document would be dead
                // until the app restarted. The transport has its own timeouts;
                // this is the one that bounds the whole operation regardless of
                // where it stalled.
                withTimeout(TIMEOUT_MS) { Entry.Loaded(fetcher.fetch(url)) }
            } catch (timeout: TimeoutCancellationException) {
                Entry.Failed("This relay did not answer in time.")
            } catch (cancelled: CancellationException) {
                // The caller went away — the screen was closed, or the account
                // switched. Leave nothing behind claiming to be in flight, or
                // this relay becomes unreadable for the rest of the session.
                state.value = state.value - url
                throw cancelled
            } catch (failure: Throwable) {
                Entry.Failed(failure.message ?: "Could not read this relay's information document")
            }
        state.value = state.value.plusBounded(url, result, StoreLimits.RELAY_INFO)
        return result
    }

    /** Drops what is known about [url] — used when a relay is forgotten. */
    fun clear(url: RelayUrl) {
        state.value = state.value - url
    }

    private companion object {
        /**
         * How long a relay gets to hand over its information document.
         *
         * Generous for a small JSON file over one HTTPS request, and short
         * enough that somebody who pressed a button gets an answer rather than
         * a spinner they have to guess about.
         */
        const val TIMEOUT_MS = 15_000L
    }
}
