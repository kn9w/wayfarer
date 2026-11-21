package app.wayfarer.core

import app.wayfarer.core.text.Markdown
import app.wayfarer.core.text.MarkdownBlock
import app.wayfarer.core.text.MarkdownSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * NIP-23 bodies are markdown, and were being shown with the marks still in
 * them. These are the shapes an article actually uses.
 */
class MarkdownTest {
    private fun blocks(source: String) = Markdown.parse(source)

    private fun plain(spans: List<MarkdownSpan>) = spans.joinToString("") { it.text }

    // ---- blocks ------------------------------------------------------------

    @Test
    fun `a heading is its level and its words, without the hashes`() {
        val heading = blocks("## The middle part").single()

        assertIs<MarkdownBlock.Heading>(heading)
        assertEquals(2, heading.level)
        assertEquals("The middle part", plain(heading.text))
    }

    @Test
    fun `a hashtag is not a heading`() {
        val block = blocks("#nostr is a tag").single()

        assertIs<MarkdownBlock.Paragraph>(block)
        assertEquals("#nostr is a tag", plain(block.text))
    }

    @Test
    fun `a closing hash run is decoration rather than part of the title`() {
        val heading = blocks("### Middle ###").single()

        assertEquals("Middle", plain((heading as MarkdownBlock.Heading).text))
    }

    /**
     * NIP-23: a long-form client MUST NOT hard line-break paragraphs, so a bare
     * newline is somebody's editor wrapping at a column of its own. Honouring it
     * would break the text at that column on every phone that is not as wide.
     */
    @Test
    fun `a soft wrap inside a paragraph is reflowed, and a blank line ends it`() {
        val parsed = blocks("one\ntwo\n\nthree")

        assertEquals(2, parsed.size)
        assertEquals("one two", plain((parsed[0] as MarkdownBlock.Paragraph).text))
        assertEquals("three", plain((parsed[1] as MarkdownBlock.Paragraph).text))
    }

    @Test
    fun `a break the author actually asked for survives the reflow`() {
        val trailingSpaces = blocks("first line  \nsecond line").single()
        val backslash = blocks("first line\\\nsecond line").single()

        assertEquals("first line\nsecond line", plain((trailingSpaces as MarkdownBlock.Paragraph).text))
        assertEquals("first line\nsecond line", plain((backslash as MarkdownBlock.Paragraph).text))
    }

    @Test
    fun `a nostr reference is a link rather than a wall of bech32 in the prose`() {
        val spans = (blocks("as nostr:npub1abcdef said").single() as MarkdownBlock.Paragraph).text

        assertEquals("nostr:npub1abcdef", spans.single { it.link != null }.link)
    }

    @Test
    fun `the reference-style link NIP-23's own example uses is read as a link`() {
        val spans = (blocks("Lorem [ipsum][nostr:nevent1qqst8] dolor").single() as MarkdownBlock.Paragraph).text

        assertEquals("Lorem ipsum dolor", plain(spans))
        assertEquals("nostr:nevent1qqst8", spans.single { it.text == "ipsum" }.link)
    }

    @Test
    fun `a fenced code block keeps its indentation and its language`() {
        val code = blocks("```kotlin\nfun main() {\n    println()\n}\n```").single()

        assertIs<MarkdownBlock.CodeBlock>(code)
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {\n    println()\n}", code.code)
    }

    @Test
    fun `an unclosed fence runs to the end rather than swallowing nothing`() {
        val code = blocks("```\nstill code\n").single()

        assertEquals("still code", (code as MarkdownBlock.CodeBlock).code)
    }

    @Test
    fun `nothing inside a code block is a delimiter`() {
        val code = blocks("```\n**not bold** and # not a heading\n```").single()

        assertEquals("**not bold** and # not a heading", (code as MarkdownBlock.CodeBlock).code)
    }

    @Test
    fun `bullets and numbers become list items with the marker to draw`() {
        val parsed = blocks("- first\n* second\n\n1. one\n2) two")

        assertEquals(listOf("•", "•", "1.", "2."), parsed.map { (it as MarkdownBlock.ListItem).marker })
        assertEquals("first", plain((parsed[0] as MarkdownBlock.ListItem).text))
    }

    @Test
    fun `an indented bullet is nested`() {
        val parsed = blocks("- top\n  - under it")

        assertEquals(0, (parsed[0] as MarkdownBlock.ListItem).depth)
        assertEquals(1, (parsed[1] as MarkdownBlock.ListItem).depth)
    }

    @Test
    fun `a quote gathers its lines and drops the markers`() {
        val quote = blocks("> first\n> second").single()

        assertIs<MarkdownBlock.Quote>(quote)
        assertEquals("first\nsecond", plain(quote.text))
    }

    @Test
    fun `a rule is a rule at three marks or more`() {
        assertIs<MarkdownBlock.Rule>(blocks("---").single())
        assertIs<MarkdownBlock.Rule>(blocks("***").single())
        assertIs<MarkdownBlock.Rule>(blocks("___").single())
        // Two is not a rule, it is text.
        assertIs<MarkdownBlock.Paragraph>(blocks("--").single())
    }

    @Test
    fun `a picture on its own line is a picture, not a sentence about one`() {
        val image = blocks("![a cat](https://cdn.example.com/cat.jpg)").single()

        assertIs<MarkdownBlock.Image>(image)
        assertEquals("https://cdn.example.com/cat.jpg", image.url)
        assertEquals("a cat", image.alt)
    }

    @Test
    fun `a block interrupts the paragraph above it without a blank line`() {
        val parsed = blocks("some words\n## a heading\nmore words")

        assertEquals(3, parsed.size)
        assertIs<MarkdownBlock.Paragraph>(parsed[0])
        assertIs<MarkdownBlock.Heading>(parsed[1])
        assertIs<MarkdownBlock.Paragraph>(parsed[2])
    }

    // ---- inline -------------------------------------------------------------

    @Test
    fun `bold and italic are read, and can nest`() {
        val spans = (blocks("plain **bold and *both* here**").single() as MarkdownBlock.Paragraph).text

        assertEquals("plain bold and both here", plain(spans))
        val both = spans.single { it.text == "both" }
        assertTrue(both.bold && both.italic)
        assertTrue(spans.first { it.text == "plain " }.let { !it.bold && !it.italic })
    }

    @Test
    fun `three marks are both emphases at once`() {
        val spans = (blocks("***loud***").single() as MarkdownBlock.Paragraph).text

        val span = spans.single()
        assertEquals("loud", span.text)
        assertTrue(span.bold && span.italic)
    }

    @Test
    fun `an unmatched delimiter is the character the author typed`() {
        val spans = (blocks("2 * 3 * 4 and **unclosed").single() as MarkdownBlock.Paragraph).text

        assertEquals("2 * 3 * 4 and **unclosed", plain(spans))
        assertTrue(spans.none { it.bold || it.italic })
    }

    @Test
    fun `underscores inside a word are part of the word`() {
        val spans = (blocks("read snake_case_names carefully").single() as MarkdownBlock.Paragraph).text

        assertEquals("read snake_case_names carefully", plain(spans))
        assertTrue(spans.none { it.italic })
    }

    @Test
    fun `code spans and strikethrough are marked`() {
        val spans = (blocks("call `parse()` and ~~forget~~ it").single() as MarkdownBlock.Paragraph).text

        assertTrue(spans.single { it.text == "parse()" }.code)
        assertTrue(spans.single { it.text == "forget" }.strikethrough)
    }

    @Test
    fun `a link carries its address on the words it was written on`() {
        val spans = (blocks("see [the spec](https://example.com/a) now").single() as MarkdownBlock.Paragraph).text

        assertEquals("see the spec now", plain(spans))
        assertEquals("https://example.com/a", spans.single { it.text == "the spec" }.link)
    }

    @Test
    fun `a bare address becomes a link without being written as one`() {
        val spans = (blocks("go to https://example.com/x, then stop").single() as MarkdownBlock.Paragraph).text

        // The comma ends the sentence, not the address.
        assertEquals("https://example.com/x", spans.single { it.link != null }.link)
        assertEquals("go to https://example.com/x, then stop", plain(spans))
    }

    @Test
    fun `an escaped mark is printed rather than obeyed`() {
        val spans = (blocks("a literal \\*asterisk\\* here").single() as MarkdownBlock.Paragraph).text

        assertEquals("a literal *asterisk* here", plain(spans))
        assertTrue(spans.none { it.italic })
    }

    @Test
    fun `a picture inside a sentence keeps its alt text and its address`() {
        val spans = (blocks("look ![a cat](https://cdn.example/c.jpg) here").single() as MarkdownBlock.Paragraph).text

        val picture = spans.single { it.link == "https://cdn.example/c.jpg" }
        assertEquals("a cat", picture.text)
    }

    @Test
    fun `an empty document parses to nothing rather than to a blank paragraph`() {
        assertEquals(emptyList(), blocks(""))
        assertEquals(emptyList(), blocks("\n\n   \n"))
    }
}
