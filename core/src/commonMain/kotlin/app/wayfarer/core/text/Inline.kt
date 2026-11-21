package app.wayfarer.core.text

/**
 * The inline half of [Markdown]: emphasis, code, links and pictures inside a
 * line of text.
 *
 * One left-to-right pass with a small recursion for nesting. Every delimiter is
 * optimistic — it is only a delimiter if its partner is found later on the same
 * block, and otherwise it is the character the author typed. That rule is what
 * makes the parser total: `2 * 3 * 4` is arithmetic, not italics, and an
 * unmatched bracket is a bracket.
 */
internal object Inline {
    /**
     * How deep emphasis may nest before the parser stops looking.
     *
     * Bold inside a link inside italics is three, and anything past that is
     * either a mistake or a document trying to make a parser work hard.
     */
    private const val MAX_DEPTH = 6

    fun parse(text: String): List<MarkdownSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = mutableListOf<MarkdownSpan>()
        parseInto(text, MarkdownSpan("", link = null), spans, depth = 0)
        return spans.filter { it.text.isNotEmpty() }
    }

    /**
     * Appends the spans of [text] to [out], every one of them carrying the
     * emphasis of [style] plus whatever it finds.
     */
    private fun parseInto(
        text: String,
        style: MarkdownSpan,
        out: MutableList<MarkdownSpan>,
        depth: Int,
    ) {
        val literal = StringBuilder()

        fun flush() {
            if (literal.isEmpty()) return
            out += style.copy(text = literal.toString())
            literal.clear()
        }

        var index = 0
        while (index < text.length) {
            val char = text[index]

            // An escape is one character of plain text, whatever it would
            // otherwise have started.
            if (char == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE) {
                literal.append(text[index + 1])
                index += 2
                continue
            }

            if (char == '`') {
                val ticks = text.drop(index).takeWhile { it == '`' }.length
                val close = text.indexOf("`".repeat(ticks), index + ticks)
                if (close > 0) {
                    flush()
                    // Code spans are literal by definition: nothing inside one is
                    // a delimiter, which is the point of writing one.
                    out += style.copy(text = text.substring(index + ticks, close).trim(), code = true)
                    index = close + ticks
                    continue
                }
            }

            if (depth < MAX_DEPTH) {
                val emphasis = emphasisAt(text, index)
                if (emphasis != null) {
                    flush()
                    parseInto(emphasis.inner, emphasis.apply(style), out, depth + 1)
                    index = emphasis.end
                    continue
                }

                if (char == '!' && index + 1 < text.length && text[index + 1] == '[') {
                    val image = imageAt(text, index)
                    if (image != null) {
                        flush()
                        // A picture inside a sentence cannot be drawn where it
                        // sits, so it becomes what it is: a link, labelled with
                        // the author's own alt text where they wrote one.
                        out += style.copy(text = image.alt.ifBlank { image.url }, link = image.url)
                        index += image.length
                        continue
                    }
                }

                if (char == '[') {
                    val link = linkAt(text, index)
                    if (link != null) {
                        flush()
                        parseInto(link.label, style.copy(link = link.url), out, depth + 1)
                        index += link.length
                        continue
                    }
                }
            }

            val autolink = autolinkAt(text, index)
            if (autolink != null) {
                flush()
                out += style.copy(text = autolink, link = autolink)
                index += autolink.length
                continue
            }

            literal.append(char)
            index++
        }

        flush()
    }

    // ---- emphasis -----------------------------------------------------------

    private class Emphasis(
        val inner: String,
        val end: Int,
        private val marker: String,
        private val char: Char,
    ) {
        fun apply(style: MarkdownSpan): MarkdownSpan =
            when {
                char == '~' -> style.copy(strikethrough = true)
                // `***both***` is the two together, which is what a run of three
                // means in every dialect and what an author reaching for it is
                // asking for.
                marker.length >= 3 -> style.copy(bold = true, italic = true)
                marker.length >= 2 -> style.copy(bold = true)
                else -> style.copy(italic = true)
            }
    }

    private fun emphasisAt(
        text: String,
        index: Int,
    ): Emphasis? {
        val char = text[index]
        if (char !in "*_~") return null

        // `snake_case_words` is a word, not three italic fragments. Underscores
        // only open emphasis at a boundary; asterisks are unambiguous.
        if (char == '_' && index > 0 && (text[index - 1].isLetterOrDigit() || text[index - 1] == '_')) return null

        val run = text.drop(index).takeWhile { it == char }.length
        // `~` is strikethrough only as a pair; a single one is a tilde, and
        // three of them are not a third thing.
        val marker = char.toString().repeat(if (char == '~') 2 else run.coerceAtMost(3))
        if (char == '~' && run < 2) return null

        val contentStart = index + marker.length
        if (contentStart >= text.length) return null
        // `** bold**` is not emphasis in any dialect, and `* ` is a bullet.
        if (text[contentStart] == ' ') return null

        val close = text.indexOf(marker, contentStart)
        if (close < 0) return null
        val inner = text.substring(contentStart, close)
        if (inner.isBlank()) return null

        return Emphasis(inner, close + marker.length, marker, char)
    }

    // ---- links and pictures -------------------------------------------------

    class Bracketed(
        val label: String,
        val url: String,
        /** How many characters of the source this consumed. */
        val length: Int,
    ) {
        /** The same string, by the name it goes by on a picture. */
        val alt: String get() = label
    }

    /**
     * `[label](url)` starting at [index], or null when it is just a bracket.
     *
     * `[label][destination]` is accepted too. That is markdown's *reference*
     * syntax, where the second bracket names a link defined elsewhere in the
     * document — but NIP-23's own example event writes a `nostr:` URI straight
     * into it, so an article in the wild is as likely to mean it literally as
     * to mean a reference this parser has no table for. Reading it as the
     * destination is right in the first case and no worse than showing raw
     * brackets in the second.
     */
    private fun linkAt(
        text: String,
        index: Int,
    ): Bracketed? {
        val labelEnd = matchingBracket(text, index) ?: return null
        if (labelEnd + 1 >= text.length) return null

        val opener = text[labelEnd + 1]
        val closer = if (opener == '(') ')' else if (opener == '[') ']' else return null
        val urlEnd = text.indexOf(closer, labelEnd + 2)
        if (urlEnd < 0) return null

        val label = text.substring(index + 1, labelEnd)
        // A title after the URL — `(url "like this")` — is not shown anywhere,
        // so it is dropped rather than becoming part of the address.
        val url = text.substring(labelEnd + 2, urlEnd).trim().substringBefore(' ')
        if (url.isEmpty()) return null
        return Bracketed(label, url, urlEnd + 1 - index)
    }

    /** `![alt](url)` starting at [index], or null. */
    fun imageAt(
        text: String,
        index: Int,
    ): Bracketed? {
        if (index + 1 >= text.length || text[index] != '!' || text[index + 1] != '[') return null
        val inner = linkAt(text, index + 1) ?: return null
        return Bracketed(inner.label, inner.url, inner.length + 1)
    }

    /** Where the `[` at [open] closes, allowing for brackets inside it. */
    private fun matchingBracket(
        text: String,
        open: Int,
    ): Int? {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    /**
     * The bare address beginning at [index], or null.
     *
     * `nostr:` counts. NIP-23 says references to other notes, articles and
     * people are made per NIP-27 — as `nostr:` URIs written straight into the
     * text — so an article that follows the specification is full of them, and
     * a reader that treats them as prose shows a wall of bech32.
     */
    private fun autolinkAt(
        text: String,
        index: Int,
    ): String? {
        val char = text[index]
        if (char != 'h' && char != 'n') return null
        val rest = text.substring(index)
        val scheme =
            SCHEMES.firstOrNull { rest.startsWith(it) } ?: return null
        // Mid-word is somebody's typo rather than an address.
        if (index > 0 && text[index - 1].isLetterOrDigit()) return null

        val url = rest.takeWhile { !it.isWhitespace() && it !in "<>\"" }.trimEnd { it in TRAILING_PUNCTUATION }
        return url.takeIf { it.length > scheme.length }
    }

    private val SCHEMES = listOf("https://", "http://", "nostr:")

    /** Characters markdown lets an author escape, and no others. */
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!>~|"

    /** Punctuation that ends a sentence rather than an address. */
    private const val TRAILING_PUNCTUATION = ".,;:!?'\"”’)]}»"
}
