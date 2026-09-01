package app.wayfarer.core.model

/**
 * What a NIP-22 comment hangs from.
 *
 * Three kinds of target, because a comment can be about a plain event, an
 * addressable one — an article's identity is `kind:pubkey:d`, not its event id,
 * so an edit does not orphan its comments — or something outside nostr entirely.
 */
sealed interface ThreadRef {
    /** `E` / `e`: a specific event. */
    data class Event(
        val id: EventId,
    ) : ThreadRef

    /** `A` / `a`: an addressable event, as `<kind>:<pubkey>:<d>`. */
    data class Address(
        val address: String,
    ) : ThreadRef

    /** `I` / `i`: a URL, a podcast GUID, a hashtag — anything not on nostr. */
    data class External(
        val value: String,
    ) : ThreadRef
}

/**
 * A NIP-22 comment (kind 1111).
 *
 * The scheme is two references, not one, and the case of the tag is what tells
 * them apart: uppercase `E`/`A`/`I`/`K`/`P` name the **root** — the thing the
 * whole thread is about — and lowercase `e`/`a`/`i`/`k`/`p` name the **parent**,
 * the single item being replied to. On a top-level comment the two point at the
 * same place; on a reply the root stays put while the parent moves down the
 * thread. Keeping both is what lets a client fetch a whole conversation with one
 * filter on the root and still rebuild its shape.
 *
 * [rootKind] and [parentKind] are strings rather than ints on purpose: NIP-22
 * requires `K` and `k`, and its own examples use `"web"` and
 * `"podcast:item:guid"` alongside numeric kinds.
 */
data class Comment(
    val id: EventId,
    val author: PubKey,
    val createdAt: Long,
    val content: String,
    val root: ThreadRef,
    val rootKind: String,
    val rootAuthor: PubKey?,
    val parent: ThreadRef,
    val parentKind: String,
    val parentAuthor: PubKey?,
    /** Relays this exact event arrived from. Grows as more relays echo it. */
    val seenOn: Set<RelayUrl> = emptySet(),
) {
    /** True when this comments on the root itself rather than on another comment. */
    val isTopLevel: Boolean get() = parent == root

    fun mergeSeenOn(more: Set<RelayUrl>) = if (more.all { it in seenOn }) this else copy(seenOn = seenOn + more)

    companion object {
        fun fromEvent(
            event: NostrEvent,
            seenOn: RelayUrl?,
        ): Comment? {
            if (event.kind != EventKind.COMMENT) return null

            val root = event.threadRef(uppercase = true) ?: return null
            val parent = event.threadRef(uppercase = false) ?: return null
            // "Tags K and k MUST be present to define the event kind of the root
            // and the parent items." Without them the reference cannot be
            // interpreted, so the comment is not one this app can place.
            val rootKind = event.tagValues("K").firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val parentKind = event.tagValues("k").firstOrNull()?.takeIf { it.isNotBlank() } ?: return null

            return Comment(
                id = event.id,
                author = event.pubKey,
                createdAt = event.createdAt,
                content = event.content,
                root = root,
                rootKind = rootKind,
                rootAuthor = PubKey.parseOrNull(event.tagValues("P").firstOrNull()),
                parent = parent,
                parentKind = parentKind,
                parentAuthor = PubKey.parseOrNull(event.tagValues("p").firstOrNull()),
                seenOn = setOfNotNull(seenOn),
            )
        }

        /**
         * The root scope (uppercase) or the parent item (lowercase).
         *
         * Address before event: NIP-22's own blog-post example carries `a` *and*
         * `e` on the parent — the address identifies the article, the event id
         * only the revision commented on — so the address is the more durable of
         * the two and the one worth keeping.
         */
        private fun NostrEvent.threadRef(uppercase: Boolean): ThreadRef? {
            fun tag(name: String) = tagValues(if (uppercase) name.uppercase() else name).firstOrNull()

            tag("a")?.takeIf { it.isNotBlank() }?.let { return ThreadRef.Address(it) }
            EventId.parseOrNull(tag("e"))?.let { return ThreadRef.Event(it) }
            tag("i")?.takeIf { it.isNotBlank() }?.let { return ThreadRef.External(it) }
            return null
        }
    }
}
