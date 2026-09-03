package app.wayfarer.core.text

/**
 * Enough markdown to read a NIP-23 article, parsed into blocks and spans.
 *
 * Long-form nostr content is markdown by specification, and Wayfarer was showing
 * it verbatim: every `##`, every `**`, every `[text](url)` on screen as typed.
 * That is not "honest plain text", it is an unrendered document — the marks are
 * the author's formatting, and leaving them in makes the article harder to read
 * than if they had never been written.
 *
 * Hand-written rather than a markdown dependency, for the reason the rest of
 * this project gives: the whole need is a reader, and a reader is one pass of
 * block detection over a pass of inline emphasis. What is deliberately not here
 * is everything a *writer* would need — tables, footnotes, reference links,
 * nested block structures, HTML. An article using those degrades to its own
 * text, which is exactly where this app started.
 *
 * It lives in the core because it is pure text and has no platform in it: the
 * app module turns [MarkdownBlock]s into composables and decides what a picture
 * is allowed to do, which is the half that cannot be tested off-device.
 */
object Markdown {
    /**
     * The blocks of [source], in order.
     *
     * Total: any input at all produces some rendering. There is no failure mode
     * where an article disappears because its markdown was malformed, because
     * an unmatched delimiter is simply literal text, which is also what the
     * author would see in any other reader.
     */
    fun parse(source: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = source.lines()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            when {
                trimmed.isEmpty() -> index++

                fenceOf(trimmed) != null -> {
                    val fence = fenceOf(trimmed)!!
                    val language = trimmed.drop(fence.length).trim().takeIf { it.isNotEmpty() }
                    val body = mutableListOf<String>()
                    index++
                    while (index < lines.size && fenceOf(lines[index].trim())?.first() != fence.first()) {
                        body += lines[index]
                        index++
                    }
                    // An unclosed fence runs to the end of the article, which is
                    // what every other reader does with one.
                    if (index < lines.size) index++
                    // Trailing blank lines are the gap before the next block, or
                    // the end of the article for a fence nobody closed.
                    while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
                    blocks += MarkdownBlock.CodeBlock(language, body.joinToString("\n"))
                }

                isRule(trimmed) -> {
                    blocks += MarkdownBlock.Rule
                    index++
                }

                headingLevel(trimmed) > 0 -> {
                    val level = headingLevel(trimmed)
                    val text = trimmed.drop(level).trim().trimEnd('#').trim()
                    blocks += MarkdownBlock.Heading(level, Inline.parse(text))
                    index++
                }

                trimmed.startsWith(">") -> {
                    val quoted = mutableListOf<String>()
                    while (index < lines.size && lines[index].trim().startsWith(">")) {
                        quoted += lines[index].trim().removePrefix(">").removePrefix(" ")
                        index++
                    }
                    blocks += MarkdownBlock.Quote(Inline.parse(quoted.joinToString("\n").trim()))
                }

                imageOnly(trimmed) != null -> {
                    blocks += imageOnly(trimmed)!!
                    index++
                }

                bulletOf(line) != null -> {
                    val (marker, content, depth) = bulletOf(line)!!
                    blocks += MarkdownBlock.ListItem(marker, Inline.parse(content), depth)
                    index++
                }

                else -> {
                    // A paragraph runs to the next blank line or the next line
                    // that starts a block of its own, and its soft wraps are
                    // reflowed. NIP-23 is explicit that a long-form client MUST
                    // NOT hard line-break paragraphs, so a bare newline is the
                    // author's editor wrapping at some column of its own and
                    // honouring it would break the text at that column on every
                    // phone that is not as wide.
                    //
                    // A break the author asked for in markdown's own way — two
                    // trailing spaces, or a trailing backslash — is kept, which
                    // is how a verse or an address block survives the reflow.
                    val paragraph = StringBuilder()
                    // Set by a line ending in markdown's own hard break, read by
                    // the line after it.
                    var hardBreakPending = false
                    while (index < lines.size) {
                        val next = lines[index]
                        if (next.isBlank()) break
                        if (paragraph.isNotEmpty() && startsBlock(next)) break

                        val hardBreak = next.endsWith("  ") || next.endsWith("\\")
                        val content = next.trim().removeSuffix("\\").trimEnd()
                        if (paragraph.isNotEmpty()) paragraph.append(if (hardBreakPending) "\n" else " ")
                        paragraph.append(content)
                        hardBreakPending = hardBreak
                        index++
                    }
                    blocks += MarkdownBlock.Paragraph(Inline.parse(paragraph.toString()))
                }
            }
        }

        return blocks
    }

    /** Whether [line] would begin a block other than the paragraph it is inside. */
    private fun startsBlock(line: String): Boolean {
        val trimmed = line.trim()
        return fenceOf(trimmed) != null ||
            isRule(trimmed) ||
            headingLevel(trimmed) > 0 ||
            trimmed.startsWith(">") ||
            imageOnly(trimmed) != null ||
            bulletOf(line) != null
    }

    /** The ``` or ~~~ run opening [trimmed], or null. */
    private fun fenceOf(trimmed: String): String? {
        for (marker in charArrayOf('`', '~')) {
            val run = trimmed.takeWhile { it == marker }
            if (run.length >= 3) return run
        }
        return null
    }

    private fun isRule(trimmed: String): Boolean {
        val bare = trimmed.filterNot { it == ' ' }
        if (bare.length < 3) return false
        val first = bare.first()
        return first in "*-_" && bare.all { it == first }
    }

    private fun headingLevel(trimmed: String): Int {
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return 0
        // `#hashtag` is not a heading. ATX requires a space after the run.
        return if (trimmed.length > hashes && trimmed[hashes] == ' ') hashes else 0
    }

    /**
     * The marker, content and indent depth of a list item, or null.
     *
     * Takes the raw line rather than a trimmed one, because the leading spaces
     * are the only thing that says a list is nested.
     */
    private fun bulletOf(line: String): Triple<String, String, Int>? {
        val indent = line.takeWhile { it == ' ' }.length + line.takeWhile { it == '\t' }.length * 4
        val trimmed = line.trim()
        val depth = (indent / 2).coerceAtMost(MAX_LIST_DEPTH)

        if (trimmed.length >= 2 && trimmed[0] in "-*+" && trimmed[1] == ' ') {
            return Triple("•", trimmed.drop(2).trim(), depth)
        }

        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.isNotEmpty() && digits.length <= 9 && trimmed.length > digits.length + 1) {
            val separator = trimmed[digits.length]
            if ((separator == '.' || separator == ')') && trimmed[digits.length + 1] == ' ') {
                return Triple("$digits.", trimmed.drop(digits.length + 2).trim(), depth)
            }
        }
        return null
    }

    /** [trimmed] as a picture, when the whole line is one `![alt](url)` and nothing else. */
    private fun imageOnly(trimmed: String): MarkdownBlock.Image? {
        if (!trimmed.startsWith("![")) return null
        val image = Inline.imageAt(trimmed, 0) ?: return null
        return if (image.length == trimmed.length) MarkdownBlock.Image(image.url, image.alt) else null
    }

    /** Deeper than this and the indent is wider than the words. */
    private const val MAX_LIST_DEPTH = 3
}

/** One piece of an article. Rendering is the app module's job. */
sealed interface MarkdownBlock {
    data class Heading(
        /** 1 to 6, as the `#` run said. */
        val level: Int,
        val text: List<MarkdownSpan>,
    ) : MarkdownBlock

    data class Paragraph(
        val text: List<MarkdownSpan>,
    ) : MarkdownBlock

    data class Quote(
        val text: List<MarkdownSpan>,
    ) : MarkdownBlock

    data class CodeBlock(
        /** The word after the opening fence, where there was one. */
        val language: String?,
        val code: String,
    ) : MarkdownBlock

    data class ListItem(
        /** "•", or "3." for an ordered list — already the string to draw. */
        val marker: String,
        val text: List<MarkdownSpan>,
        val depth: Int = 0,
    ) : MarkdownBlock

    /** A picture on a line of its own. What the app does with it is a permission question. */
    data class Image(
        val url: String,
        val alt: String,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock
}

/**
 * A run of text with the emphasis that applies to it.
 *
 * Flat rather than a tree: nesting is resolved during parsing, so `**bold and
 * *also italic***` arrives as two spans, the second with both flags set. A flat
 * list is what an `AnnotatedString` is built from anyway, and a tree would only
 * be flattened again on the other side.
 */
data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    /** The URL this run points at, for a link or an autolinked address. */
    val link: String? = null,
)
