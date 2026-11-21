package app.wayfarer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.platform.MediaUrls
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.shortenNpub
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.text.Markdown
import app.wayfarer.core.text.MarkdownBlock
import app.wayfarer.core.text.MarkdownSpan

/**
 * A NIP-23 article body, rendered.
 *
 * The parsing is [Markdown]'s, in the core where it can be tested against real
 * documents; this file is the half that needs a screen — what type style a
 * heading level takes, what a quote looks like — plus the one question that is
 * not typography at all. A picture in an article is a request to somebody's
 * server, so it goes through the same gate every other picture in this app does
 * and appears where the author put it once its host is allowed.
 *
 * Links are styled and do not open anything. This app hands nothing to a browser
 * or to another app anywhere else, and a link in an article would be a strange
 * place to start; a bare address is its own text, so it stays readable and can
 * be typed out. `nostr:` references are labelled with the name of whoever they
 * point at, which is the whole reason NIP-23 says to write them that way.
 */
@Composable
fun MarkdownBody(
    source: String,
    controller: AppController,
    modifier: Modifier = Modifier,
    onOpenMedia: (MediaHost) -> Unit,
) {
    val blocks = remember(source) { Markdown.parse(source) }
    if (blocks.isEmpty()) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> Heading(block, controller)

                is MarkdownBlock.Paragraph -> Paragraph(block, controller, onOpenMedia)

                is MarkdownBlock.Quote -> Quote(block, controller)

                is MarkdownBlock.CodeBlock -> CodeBlock(block)

                is MarkdownBlock.ListItem -> ListItem(block, controller)

                is MarkdownBlock.Image ->
                    PostImage(
                        url = block.url,
                        controller = controller,
                        video = MediaUrls.mediaIn(block.url).singleOrNull()?.video == true,
                        onOpenMedia = onOpenMedia,
                    )

                MarkdownBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun Paragraph(
    block: MarkdownBlock.Paragraph,
    controller: AppController,
    onOpenMedia: (MediaHost) -> Unit,
) {
    // A paragraph that is nothing but one picture's address *is* a picture.
    // Authors write them both ways — `![](url)` and the bare address on a line
    // of its own — and only the first is markdown, so the second is recognised
    // here, where the app knows which addresses are pictures at all.
    val bare = block.text.singleOrNull()?.takeIf { it.link != null && it.text == it.link }?.link
    val picture = bare?.let { MediaUrls.mediaIn(it).singleOrNull() }

    if (picture != null) {
        PostImage(
            url = picture.url,
            controller = controller,
            video = picture.video,
            onOpenMedia = onOpenMedia,
        )
    } else {
        Text(annotated(block.text, controller), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Heading(
    block: MarkdownBlock.Heading,
    controller: AppController,
) {
    // Four steps for six levels. Past the third, a heading in an article on a
    // phone is a label rather than a size, and three more type styles to
    // separate h4 from h6 would be a difference nobody could see.
    val style =
        when (block.level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            3 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleSmall
        }
    Text(annotated(block.text, controller), style = style, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Quote(
    block: MarkdownBlock.Quote,
    controller: AppController,
) {
    // IntrinsicSize.Min so the rule beside the text is exactly as tall as the
    // text: an indent alone, on a phone, is four characters of a forty-character
    // line, which reads as a typo rather than as a quotation.
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Text(
            annotated(block.text, controller),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.CodeBlock) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        block.language?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Scrolls sideways rather than wrapping. Code wrapped at the screen's
        // width is code whose indentation has stopped meaning anything.
        Text(
            block.code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ListItem(
    block: MarkdownBlock.ListItem,
    controller: AppController,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = (block.depth * 16).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            block.marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Text(annotated(block.text, controller), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The spans of one block, as text Compose can draw.
 *
 * Composable because two of its answers come from outside the parser: the theme
 * decides what a link and a code span look like, and the profile cache decides
 * what a `nostr:` reference is called.
 */
@Composable
private fun annotated(
    spans: List<MarkdownSpan>,
    controller: AppController,
): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val profiles by controller.profiles.collectAsStateWithLifecycle()

    return buildAnnotatedString {
        for (span in spans) {
            pushStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    color = if (span.link != null) colors.primary else Color.Unspecified,
                    background = if (span.code) colors.surfaceContainerHigh else Color.Unspecified,
                    textDecoration =
                        when {
                            span.strikethrough && span.link != null ->
                                TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                            span.strikethrough -> TextDecoration.LineThrough
                            span.link != null -> TextDecoration.Underline
                            else -> null
                        },
                )
            )
            append(labelFor(span, controller, profiles))
            pop()
        }
    }
}

/**
 * What a span reads as.
 *
 * Usually its own text. The exception is a `nostr:` reference written out in
 * full: NIP-23 says references to people and posts are made as `nostr:` URIs in
 * the prose, so an article that follows the specification carries sixty
 * characters of bech32 mid-sentence where a name belongs.
 *
 * [profiles] is passed rather than read through the controller so that a name
 * arriving after the article was drawn redraws it — a reference is written
 * before that person's kind 0 has necessarily been fetched, and the common case
 * is that it lands a moment later.
 */
private fun labelFor(
    span: MarkdownSpan,
    controller: AppController,
    profiles: Map<PubKey, Profile>,
): String {
    val link = span.link ?: return span.text
    // Only a reference the author left bare. `[the spec](nostr:…)` already has
    // the words they chose for it.
    if (span.text != link || !link.startsWith("nostr:")) return span.text

    val entity = link.removePrefix("nostr:")
    val pubKey = controller.pubKeyOf(entity) ?: return shorten(entity)
    return "@" + (profiles[pubKey]?.displayNameOrNull() ?: shortenNpub(controller.npubFor(pubKey)))
}

/** A bech32 entity that is not a person, cut to something skimmable. */
private fun shorten(entity: String): String = if (entity.length <= 20) entity else entity.take(10) + "…"
