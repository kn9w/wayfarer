package app.wayfarer.core.model

/**
 * A relay address in the single normalized form the whole app uses as an
 * identity key.
 *
 * Normalization itself is a platform concern (it needs real URL parsing, IDN,
 * IPv6 literals, .onion detection) and is supplied by a [app.wayfarer.core.nostr.RelayUrlNormalizer].
 * By the time a string is wrapped here it is assumed to already be normalized:
 * two [RelayUrl]s are equal exactly when they name the same relay, which is
 * what makes the permission map trustworthy.
 */
@JvmInline
value class RelayUrl(
    val url: String,
) : Comparable<RelayUrl> {
    /** `wss://relay.example.com/` -> `relay.example.com` */
    fun display(): String =
        url
            .removePrefix("wss://")
            .removePrefix("ws://")
            .removeSuffix("/")

    override fun compareTo(other: RelayUrl): Int = url.compareTo(other.url)
}
