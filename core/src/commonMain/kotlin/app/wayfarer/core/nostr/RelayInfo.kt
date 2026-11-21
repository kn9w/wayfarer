package app.wayfarer.core.nostr

import app.wayfarer.core.model.RelayUrl

/**
 * A relay's NIP-11 information document, reduced to what is worth showing
 * someone deciding whether to approve the relay.
 */
data class RelayInfo(
    val url: RelayUrl,
    val name: String?,
    val description: String?,
    val software: String?,
    val version: String?,
    /** NIP numbers the relay claims to support. */
    val supportedNips: List<Int>,
    val authRequired: Boolean,
    val paymentRequired: Boolean,
    val maxMessageLength: Int?,
    val postingPolicy: String?,
    val paymentsUrl: String?,
)

/**
 * Fetches a relay's NIP-11 document.
 *
 * This is an HTTPS request to the relay host, which is why it is deliberately
 * not folded into [RelayTransport]: connecting a websocket and asking a relay to
 * describe itself are separately consented actions in this app. See
 * [app.wayfarer.core.relay.RelayInfoService].
 */
fun interface RelayInfoFetcher {
    suspend fun fetch(url: RelayUrl): RelayInfo
}
