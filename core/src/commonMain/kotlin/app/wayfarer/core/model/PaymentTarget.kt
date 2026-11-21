package app.wayfarer.core.model

/**
 * One place somebody says they can be paid — a NIP-A3 `payto` tag.
 *
 * The whole of a kind 10133 lives in its tags, each one
 * `["payto", "<type>", "<address>"]`, with the type lowercase. That is why this
 * is parsed here rather than through [app.wayfarer.core.nostr.NostrCodec]: there
 * is no JSON body to read and nothing platform-specific to do, unlike a kind 0
 * or a kind 10002.
 *
 * [type] is deliberately a string rather than an enum. The NIP names thirteen
 * common ones and says outright that others may appear; an enum would mean
 * dropping a target this app happened not to know about, which is the opposite
 * of what somebody publishing an address wants.
 */
data class PaymentTarget(
    /** `bitcoin`, `lightning`, `monero`… lowercase, as the NIP requires. */
    val type: String,
    /** An address, an invoice endpoint, a username — whatever [type] means. */
    val address: String,
) {
    /**
     * The canonical URI for this target, per NIP-A3.
     *
     * A scheme of its own where the type has one that carries an address, and
     * RFC-8905's `payto://<type>/<address>` for everything else — including
     * every type this app has never heard of, which is the point of the
     * fallback existing.
     */
    fun uri(): String = if (type in DIRECT_SCHEMES) "$type:$address" else "payto://$type/$address"

    companion object {
        /**
         * Types whose own URI scheme takes a bare address.
         *
         * Kept short and boring on purpose. A scheme guessed wrong produces a
         * link that silently goes nowhere, and the `payto://` fallback is always
         * correct — so a type earns a place here by being an established
         * address-carrying scheme, not by being popular.
         */
        private val DIRECT_SCHEMES = setOf("bitcoin", "ethereum", "litecoin", "monero", "zcash")

        /**
         * The types the NIP names, for the editor to offer.
         *
         * An offer, not a limit: [fromEvent] reads any type at all, and a target
         * already carrying an unlisted type keeps it through an edit.
         */
        val COMMON_TYPES =
            listOf(
                "lightning",
                "bitcoin",
                "bip353",
                "bip352",
                "cashme",
                "ethereum",
                "litecoin",
                "monero",
                "nano",
                "paypal",
                "revolut",
                "solana",
                "venmo",
                "zcash",
            )

        /**
         * Every valid `payto` tag on [event], in document order and without
         * duplicates.
         *
         * A row missing its type or address is skipped rather than kept as a
         * blank: this list is rendered as somewhere to send money, and half of
         * an address is worse than none.
         */
        fun fromEvent(event: NostrEvent): List<PaymentTarget> {
            val targets = LinkedHashSet<PaymentTarget>()
            for (row in event.tagRows("payto")) {
                val type = row.getOrNull(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
                val address = row.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                // A type with whitespace or a slash in it would break the URI it
                // is about to be interpolated into.
                if (type.any { it.isWhitespace() || it == '/' || it == ':' }) continue
                targets += PaymentTarget(type, address)
            }
            return targets.toList()
        }

        /** The tag rows for a kind 10133 carrying [targets]. */
        fun toTags(targets: List<PaymentTarget>): List<List<String>> =
            targets.map { listOf("payto", it.type, it.address) }
    }
}
