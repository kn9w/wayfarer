package app.wayfarer.android.viewmodel

import app.wayfarer.core.model.Article
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl

/**
 * A note or an article, in one list.
 *
 * The two are separate types in `core` because they are separate kinds with
 * different identity rules — a note is its event id, an article is its address —
 * and merging them there would lose that. But to a reader they are both just
 * something somebody wrote, so the browsing screens want them interleaved by
 * time. That flattening is a display concern, which is why it lives here and not
 * in the model.
 */
sealed interface FeedItem {
    val author: PubKey

    /** When it was written, in the sense the reader means. */
    val createdAt: Long

    /** Which relays delivered it. */
    val seenOn: Set<RelayUrl>

    /** Stable across refetches, so a paging index can be pinned to it. */
    val key: String

    data class Post(
        val note: Note,
    ) : FeedItem {
        override val author get() = note.author
        override val createdAt get() = note.createdAt
        override val seenOn get() = note.seenOn
        override val key get() = "note:" + note.id.hex
    }

    data class LongForm(
        val article: Article,
    ) : FeedItem {
        override val author get() = article.author

        // publishedAt, not createdAt: an edit republishes an article with a new
        // createdAt, and sorting by that would jump an old piece to the top of
        // the list every time its author fixed a typo.
        override val createdAt get() = article.publishedAt
        override val seenOn get() = article.seenOn
        override val key get() = "article:" + article.address
    }
}

/** Newest first — the only order that reads naturally for one person's writing. */
fun mergeNewestFirst(
    notes: Collection<Note>,
    articles: Collection<Article>,
): List<FeedItem> =
    (notes.map { FeedItem.Post(it) } + articles.map { FeedItem.LongForm(it) })
        .sortedByDescending { it.createdAt }
