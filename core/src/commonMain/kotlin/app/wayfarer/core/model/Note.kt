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
    /**
     * The event this note's whole thread hangs from, if it is a reply.
     *
     * Distinct from [replyTo], which is the immediate parent. A reply to a reply
     * names its parent there and the conversation's origin here, and only the
     * latter can gather a thread: filtering a fetched conversation by [replyTo]
     * alone keeps the direct replies and silently drops every nested one.
     */
    val threadRoot: EventId? = null,
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
                threadRoot = threadRootOf(event),
            )
        }

        /**
         * NIP-10: prefer the e-tag explicitly marked "reply", else the marked
         * "root", else the last unmarked e-tag (the deprecated positional form).
         */
        /**
         * The thread's origin: NIP-10's `root` marker, or the first e-tag under
         * the deprecated positional scheme, where position carries the meaning
         * ("first is root, last is the direct reply").
         *
         * Falls back to [replyTarget], because a direct reply to a top-level
         * note is its own thread's root reference — such a note carries a single
         * e-tag marked `root` and no separate parent.
         */
        private fun threadRootOf(event: NostrEvent): EventId? {
            val eTags = event.tagRows("e").filter { it.size >= 2 }
            eTags.firstOrNull { it.getOrNull(3) == "root" }?.let { return EventId.parseOrNull(it[1]) }
            val positional = eTags.filter { it.getOrNull(3) == null }
            if (positional.size > 1) return EventId.parseOrNull(positional.first()[1])
            return replyTarget(event)
        }

        private fun replyTarget(event: NostrEvent): EventId? {
            val eTags = event.tagRows("e").filter { it.size >= 2 }
            eTags.firstOrNull { it.getOrNull(3) == "reply" }?.let { return EventId.parseOrNull(it[1]) }
            eTags.firstOrNull { it.getOrNull(3) == "root" }?.let { return EventId.parseOrNull(it[1]) }
            return eTags.lastOrNull { it.getOrNull(3) == null }?.let { EventId.parseOrNull(it[1]) }
        }
    }
}
