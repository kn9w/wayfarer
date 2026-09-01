package app.wayfarer.core.model

/**
 * A NIP-23 long-form article (kind 30023).
 *
 * Kind 30023 is an *addressable* event: it is identified by
 * `(kind, author, dTag)` rather than by event id, and a later event with the
 * same address replaces the earlier one. [dTag] is therefore the article's
 * stable identity, and preserving it across an edit is what makes the edit a
 * replacement rather than a second article.
 */
data class Article(
    val id: EventId,
    val author: PubKey,
    val dTag: String,
    val title: String,
    val summary: String?,
    val image: String?,
    /** Author-declared publication time. Falls back to [createdAt] when absent. */
    val publishedAt: Long,
    /** Markdown body, per NIP-23. Rendered as plain text here. */
    val content: String,
    val createdAt: Long,
    val seenOn: Set<RelayUrl> = emptySet(),
) {
    /** `30023:<pubkey>:<dTag>` — the NIP-01 address that identifies this article. */
    val address: String get() = "${EventKind.LONG_FORM}:${author.hex}:$dTag"

    fun mergeSeenOn(more: Set<RelayUrl>) = if (more.all { it in seenOn }) this else copy(seenOn = seenOn + more)
}

/** The editable fields, as the article form submits them. */
data class ArticleDraft(
    val title: String,
    val summary: String,
    val image: String,
    val content: String,
    /**
     * Empty for a new article — the repository generates a slug. Carried
     * unchanged when editing, so the published event replaces the original
     * instead of creating a new one.
     */
    val dTag: String = "",
) {
    companion object {
        fun from(article: Article) =
            ArticleDraft(
                title = article.title,
                summary = article.summary.orEmpty(),
                image = article.image.orEmpty(),
                content = article.content,
                dTag = article.dTag,
            )
    }
}
