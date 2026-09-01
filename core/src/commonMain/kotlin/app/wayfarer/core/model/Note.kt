package app.wayfarer.core.model

/**
 * A kind 1 text note as the feed shows it, plus the provenance the outbox model
 * makes interesting: which relays actually delivered this note to us.
 */
data class Note(
    val id: EventId,
    val author: PubKey,
    val createdAt: Long,
    val content: String,
    /** Relays this exact event arrived from. Grows as more relays echo it. */
    val seenOn: Set<RelayUrl> = emptySet(),
    /** e-tag of the note being replied to, if any (NIP-10 "reply" marker or last e-tag). */
    val replyTo: EventId? = null,
) {
    fun mergeSeenOn(more: Set<RelayUrl>) = if (more.all { it in seenOn }) this else copy(seenOn = seenOn + more)

    companion object {
        fun fromEvent(
            event: NostrEvent,
            seenOn: RelayUrl?,
        ): Note? {
            if (event.kind != EventKind.TEXT_NOTE) return null
            return Note(
                id = event.id,
                author = event.pubKey,
                createdAt = event.createdAt,
                content = event.content,
                seenOn = setOfNotNull(seenOn),
                replyTo = replyTarget(event),
            )
        }

        /**
         * NIP-10: prefer the e-tag explicitly marked "reply", else the marked
         * "root", else the last unmarked e-tag (the deprecated positional form).
         */
        private fun replyTarget(event: NostrEvent): EventId? {
            val eTags = event.tagRows("e").filter { it.size >= 2 }
            eTags.firstOrNull { it.getOrNull(3) == "reply" }?.let { return EventId.parseOrNull(it[1]) }
            eTags.firstOrNull { it.getOrNull(3) == "root" }?.let { return EventId.parseOrNull(it[1]) }
            return eTags.lastOrNull { it.getOrNull(3) == null }?.let { EventId.parseOrNull(it[1]) }
        }
    }
}
