package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayInfo
import app.wayfarer.core.nostr.RelayInfoFetcher
import com.vitorpamplona.quartz.nip11RelayInfo.CachedNip11Fetcher
import com.vitorpamplona.quartz.nip11RelayInfo.OkHttpNip11Fetcher
import okhttp3.OkHttpClient

/**
 * NIP-11 relay information, via Quartz's OkHttp fetcher.
 *
 * Note what this is: an HTTPS GET to the relay's host with an
 * `Accept: application/nostr+json` header. It is a real connection, which is
 * why the core only ever calls it from an explicit user action — see
 * `RelayInfoService`.
 */
class QuartzRelayInfoFetcher(
    okHttpClient: OkHttpClient = nip11Client(),
) : RelayInfoFetcher {
    private val fetcher = CachedNip11Fetcher(OkHttpNip11Fetcher { okHttpClient })

    override suspend fun fetch(url: RelayUrl): RelayInfo {
        val document = fetcher.fetch(url.toQuartz())
        return RelayInfo(
            url = url,
            name = document.name?.takeIf { it.isNotBlank() },
            description = document.description?.takeIf { it.isNotBlank() },
            software = document.software?.takeIf { it.isNotBlank() },
            version = document.version?.takeIf { it.isNotBlank() },
            // Relays publish these as numbers, but the field is loosely typed in
            // the wild, so anything unparseable is dropped rather than failing
            // the whole document.
            supportedNips = document.supported_nips.orEmpty().mapNotNull { it.trim().toIntOrNull() }.sorted(),
            authRequired = document.limitation?.auth_required == true,
            paymentRequired = document.limitation?.payment_required == true,
            maxMessageLength = document.limitation?.max_message_length,
            postingPolicy = document.posting_policy?.takeIf { it.isNotBlank() },
            paymentsUrl = document.payments_url?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        /**
         * The transport's client, with redirects switched off.
         *
         * The consent this request spends is for a named host — the dialog says
         * which one, and the user pressed "Fetch relay info" against that name.
         * A redirect spends it somewhere else: a 302 to any third party, which
         * then has the user's IP address without ever having appeared on the
         * approval screen. `followSslRedirects` goes too, or the https-to-http
         * case would survive on its own.
         *
         * Built with `newBuilder` so the connection pool and dispatcher are
         * shared with the websocket client rather than duplicated.
         */
        fun nip11Client(): OkHttpClient =
            QuartzRelayTransport
                .defaultOkHttpClient()
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }
}
