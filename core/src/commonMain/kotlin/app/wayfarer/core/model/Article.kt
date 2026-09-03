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
    /**
     * When this article was *first* published — NIP-23's `published_at`.
     *
     * Distinct from [createdAt], which the same NIP defines as the date of the
     * last update: an addressable event is replaced in place, so the only record
     * that an article predates its newest revision is this tag. Falls back to
     * [createdAt] when the author's client never wrote one, which reads as
     * "never edited" and is the best available answer.
     */
    val publishedAt: Long,
    /** Markdown body, per NIP-23. Parsed by `app.wayfarer.core.text.Markdown`. */
    val content: String,
    val createdAt: Long,
    /** NIP-23 `t` tags: what the article is about, in the author's words. */
    val topics: List<String> = emptyList(),
    val seenOn: Set<RelayUrl> = emptySet(),
) {
    /** `30023:<pubkey>:<dTag>` — the NIP-01 address that identifies this article. */
    val address: String get() = "${EventKind.LONG_FORM}:${author.hex}:$dTag"

    /**
     * True when this is a revision rather than the original.
     *
     * A minute of slack, because `published_at` and `created_at` are written by
     * the same client in the same breath on a first publish and need not land on
     * the same second.
     */
    val edited: Boolean get() = createdAt - publishedAt > EDIT_TOLERANCE_SECONDS

    fun mergeSeenOn(more: Set<RelayUrl>) = if (more.all { it in seenOn }) this else copy(seenOn = seenOn + more)
}

/** How far apart the two timestamps may be before one counts as an edit. */
private const val EDIT_TOLERANCE_SECONDS = 60L

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
    /**
     * The original publication time, carried through an edit.
     *
     * Zero for a new article, which the codec reads as "now". Without this a
     * revision published from here would stamp `published_at` with the moment of
     * the edit, which is the one thing that tag exists not to say — and it would
     * take the article's real age with it.
     */
    val publishedAt: Long = 0,
    /**
     * The article's `t` tags, carried through an edit untouched.
     *
     * Not editable here, and kept for the same reason a profile's payment
     * fields are: republishing an addressable event replaces the whole of it, so
     * a field this app does not offer to change is a field it must not drop.
     */
    val topics: List<String> = emptyList(),
) {
    companion object {
        fun from(article: Article) =
            ArticleDraft(
                title = article.title,
                summary = article.summary.orEmpty(),
                image = article.image.orEmpty(),
                content = article.content,
                dTag = article.dTag,
                publishedAt = article.publishedAt,
                topics = article.topics,
            )
    }
}
