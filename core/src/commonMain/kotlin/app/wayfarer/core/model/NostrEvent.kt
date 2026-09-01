package app.wayfarer.core.model

/**
 * A signed nostr event, in the app's own shape.
 *
 * This exists so the core never holds a Quartz `Event`. It carries only what
 * NIP-01 defines; anything kind-specific is derived by the readers in
 * [app.wayfarer.core.model] rather than by subclassing.
 */
data class NostrEvent(
    val id: EventId,
    val pubKey: PubKey,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** All values of the single-letter tag [name], in document order. */
    fun tagValues(name: String): List<String> = tags.mapNotNull { if (it.size >= 2 && it[0] == name) it[1] else null }

    /** The full tag rows named [name], for tags whose extra positions matter (`r`, `e`). */
    fun tagRows(name: String): List<List<String>> = tags.filter { it.isNotEmpty() && it[0] == name }

    /** Every pubkey this event addresses — the p-tags that drive outbox fan-out. */
    fun mentionedPubKeys(): Set<PubKey> = tagValues("p").mapNotNullTo(mutableSetOf(), PubKey::parseOrNull)
}

/** An unsigned event, as handed to a signer. */
data class UnsignedEvent(
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
    val createdAt: Long,
)

/** The event kinds this app reads or writes. Nothing else is subscribed to. */
object EventKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1

    /** NIP-22 comment. Threads under anything, including another comment. */
    const val COMMENT = 1111
    const val CONTACT_LIST = 3
    const val RELAY_LIST = 10002
    const val LONG_FORM = 30023
}
