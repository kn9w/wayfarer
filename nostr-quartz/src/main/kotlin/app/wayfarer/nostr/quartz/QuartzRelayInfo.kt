package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayInfo
import app.wayfarer.core.nostr.RelayInfoFetcher
import com.vitorpamplona.quartz.nip11RelayInfo.CachedNip11Fetcher
import com.vitorpamplona.quartz.nip11RelayInfo.OkHttpNip11Fetcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

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
         * The client this one request goes out on.
         *
         * Built from scratch rather than from the transport's. That client is
         * shaped for a websocket held open indefinitely — `readTimeout(0)`, a
         * ping interval — and inheriting it meant a relay that accepted the
         * connection and then said nothing held this request open forever, with
         * no timeout anywhere in the path.
         *
         * Redirects are off, both kinds. The consent this request spends is for
         * a named host: the dialog says which one, and the user pressed "Fetch
         * relay info" against that name. A redirect spends it somewhere else —
         * a 302 to any third party, which then has the user's IP address
         * without ever having appeared on the approval screen.
         * `followSslRedirects` goes too, or the https-to-http case survives on
         * its own.
         */
        fun nip11Client(): OkHttpClient =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(BoundedBody(MAX_DOCUMENT_BYTES))
                .build()

        /**
         * The most this will read for one information document.
         *
         * A NIP-11 document is a small JSON object describing a relay. This is
         * orders of magnitude more than any real one and still far below
         * anything that threatens the heap.
         */
        private const val MAX_DOCUMENT_BYTES = 512L * 1024
    }
}

/**
 * Refuses a response body larger than [max] before it reaches a parser.
 *
 * The same reasoning as the cap in the app's image loader, applied to the other
 * thing this app downloads over plain HTTP. A relay is an unauthenticated third
 * party, and the JSON parser behind this fetch will buffer whatever it is given:
 * without a bound, a host that answers a request for a small description
 * document with a gigabyte takes the app down. The relay does not even have to
 * be hostile to do it — a misconfigured server returning an HTML error page or a
 * log file is enough.
 *
 * Bounded here rather than at the call site because the fetch itself is Quartz's
 * code, which this module does not control. An interceptor is the one place the
 * body can be capped without owning the parser.
 */
internal class BoundedBody(
    private val max: Long,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body

        // A declared length over the cap is refused without reading anything at
        // all; an absent or lying one falls through to the real check below.
        if (body.contentLength() > max) {
            response.close()
            throw IOException("This relay's information document is too large to read.")
        }

        val source = body.source()
        source.request(max + 1)
        if (source.buffer.size > max) {
            response.close()
            throw IOException("This relay's information document is too large to read.")
        }

        // Everything is buffered by now, so this reads the whole body and no
        // more. Handing back a fresh body keeps the response usable by the
        // caller that asked for it.
        val bytes = source.readByteArray()
        return response
            .newBuilder()
            .body(bytes.toResponseBody(body.contentType()))
            .build()
    }
}
