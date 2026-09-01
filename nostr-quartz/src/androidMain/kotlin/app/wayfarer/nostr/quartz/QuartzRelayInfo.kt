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
    okHttpClient: OkHttpClient = QuartzRelayTransport.defaultOkHttpClient(),
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
}
