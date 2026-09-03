package app.wayfarer.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.ScreenHeader
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.ui.ArticleHeaderImage
import app.wayfarer.android.ui.MarkdownBody
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.android.viewmodel.ThreadState
import app.wayfarer.android.viewmodel.rootRefOfArticle
import app.wayfarer.core.model.Article
import app.wayfarer.core.model.EventKind

/**
 * A long-form article in a list: title, summary, and what it is.
 *
 * Flat, like [NoteRow], and for the same reason — an article is a post, and the
 * feed separates posts with a rule rather than giving each one a panel.
 */
@Composable
fun ArticleRow(
    article: Article,
    controller: AppController,
    onOpen: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = PostHorizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Trail, not the grey every other secondary line uses. In a feed that
        // mixes notes and articles this kicker is the only thing that says which
        // one you are looking at before you read the title, and it was the same
        // colour as the "seen on" line under it.
        Text(
            "long-form article",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(article.title, style = MaterialTheme.typography.titleMedium)
        article.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        PostByline(
            author = article.author,
            createdAt = article.publishedAt,
            controller = controller,
            onOpenAuthor = { controller.openProfile(article.author) },
            trailing = { EventMenu(article.id, controller) },
        )
        Text(
            "seen on " + article.seenOn.joinToString(", ") { it.display() }.ifBlank { "this device" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun ReadArticleScreen(
    controller: AppController,
    address: String,
) {
    val articles by controller.articles.collectAsStateWithLifecycle()
    val account by controller.account.collectAsStateWithLifecycle()
    val article = articles.firstOrNull { it.address == address }

    if (article == null) {
        Text("This article is no longer loaded.", Modifier.padding(12.dp))
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(article.title, style = MaterialTheme.typography.headlineSmall)
        article.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        // The author's name, and only their name. The face belongs on the
        // profile this opens, not on the byline of everything they wrote.
        Text(
            controller.displayName(article.author),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable { controller.openProfile(article.author) },
        )
        ArticleDates(article, controller)
        ArticleTopics(article)

        // The article's own header picture, where its host is allowed. Queued
        // by the article arriving, like every other picture the app takes in.
        ArticleHeaderImage(article = article, controller = controller)

        // NIP-23 says the body is markdown, and it was on screen with the marks
        // still in it — every `##` and `**` as the author typed them. Parsed in
        // the core, drawn here, with the pictures it names appearing where they
        // were written rather than gathered into a heap at the end.
        MarkdownBody(
            source = article.content,
            controller = controller,
            onOpenMedia = { host -> controller.openMediaHost(host, "a picture in \"${article.title}\"") },
        )

        if (account?.pubKey == article.author) {
            Button(onClick = { controller.go(Screen.EditArticle(article.address)) }) {
                Text("Edit this article")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ArticleComments(article, controller)
    }
}

/**
 * When the article was written, and whether it has changed since.
 *
 * Both, when they differ. NIP-23 is explicit about which is which — the
 * `published_at` tag is the first publication and `created_at` is the date of
 * the last update — and an addressable event is replaced in place, so without
 * saying so a revision published years later reads as a new article with the
 * original date nowhere on screen.
 *
 * Dates rather than ages. "3d" answers "have I seen this?", which is a feed's
 * question; an article's is when it was written, and two of those in different
 * units on one line would be unreadable.
 */
@Composable
private fun ArticleDates(
    article: Article,
    controller: AppController,
) {
    Text(
        buildString {
            append("Published ")
            append(controller.dateOf(article.publishedAt))
            if (article.edited) {
                append(" · updated ")
                append(controller.dateOf(article.createdAt))
            }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The article's `t` tags — what NIP-23 calls its topics.
 *
 * Read and shown, never edited here: republishing an addressable event replaces
 * the whole of it, so they are carried through an edit untouched rather than
 * being quietly dropped by a form that does not mention them.
 */
@Composable
private fun ArticleTopics(article: Article) {
    if (article.topics.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (topic in article.topics) {
            Text(
                "#$topic",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * NIP-22 comments under an article.
 *
 * Rooted on the address rather than the event id, because an article's identity
 * is `kind:pubkey:d` — publishing a correction replaces the event, and comments
 * hung from the old id would be orphaned by a typo fix.
 */
@Composable
private fun ArticleComments(
    article: Article,
    controller: AppController,
) {
    val root = rootRefOfArticle(article.address)
    val threads by controller.threads.threads.collectAsStateWithLifecycle()
    val state = threads[root] ?: ThreadState()
    var draft by remember(article.address) { mutableStateOf("") }

    // Unlike a note in a feed, an article is a whole screen the reader chose to
    // open, so its conversation is worth fetching without being asked twice.
    LaunchedEffect(root) { controller.threads.open(root) }

    Text("Comments", style = MaterialTheme.typography.titleSmall)
    when {
        state.loading && state.entries.isEmpty() ->
            Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.loaded && state.entries.isEmpty() ->
            Text(
                "Nothing yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
    for (entry in state.entries) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PostByline(
                author = entry.author,
                createdAt = entry.createdAt,
                controller = controller,
                onOpenAuthor = { controller.openProfile(entry.author) },
            )
            Text(entry.content, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    ReplyComposer(
        draft = draft,
        onDraft = { draft = it },
        posting = state.posting,
        onSend = {
            controller.threads.reply(
                root = root,
                rootKind = EventKind.LONG_FORM.toString(),
                rootAuthor = article.author,
                parent = root,
                parentKind = EventKind.LONG_FORM.toString(),
                parentAuthor = article.author,
                content = draft,
            )
            draft = ""
        },
    )
}

@Composable
fun EditArticleScreen(
    controller: AppController,
    address: String?,
) {
    val busy by controller.busy.collectAsStateWithLifecycle()
    val initial = remember(address) { controller.articleDraft(address) }
    var draft by remember(address) { mutableStateOf(initial) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(
            title = {
                Text(
                    if (address == null) "New article" else "Edit article",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
        )
        Column(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.summary,
                onValueChange = { draft = draft.copy(summary = it) },
                label = { Text("Summary") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.image,
                onValueChange = { draft = draft.copy(image = it) },
                label = { Text("Header image URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.content,
                onValueChange = { draft = draft.copy(content = it) },
                label = { Text("Body (markdown)") },
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                if (draft.dTag.isBlank()) {
                    "Publishing creates a new article at a fresh address."
                } else {
                    "Publishing replaces the existing article at ${draft.dTag}."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { controller.publishArticle(draft) },
                    enabled = !busy && draft.title.isNotBlank() && draft.content.isNotBlank(),
                ) { Text("Publish") }
                TextButton(onClick = { controller.back() }) { Text("Cancel") }
            }
        }
    }
}
