package app.wayfarer.core

import app.wayfarer.core.model.ArticleDraft
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.repo.ArticleRepository
import app.wayfarer.core.repo.PublishResult
import app.wayfarer.core.repo.RelayListRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticleRepositoryTest {
    private val alice = pubKey(1)
    private val clock = FakeClock()

    private fun repo(
        transport: FakeTransport = FakeTransport(),
        codec: FakeCodec = FakeCodec(),
        directory: RelayDirectory = RelayDirectory(clock),
    ): ArticleRepository {
        val cache = RelayListCache()
        val router = OutboxRouter(cache, directory)
        val relayLists = RelayListRepository(transport, codec, cache, router, directory, clock)
        return ArticleRepository(transport, codec, router, relayLists, clock)
    }

    @Test
    fun `an article is stored under its address, not its event id`() {
        val articles = repo()

        articles.absorb(articleEvent(alice, "my-post", "First", createdAt = 100), relay("a.example"))

        assertEquals(setOf("30023:${alice.hex}:my-post"), articles.all.value.keys)
    }

    @Test
    fun `a newer revision at the same address replaces the older one`() {
        val articles = repo()
        articles.absorb(articleEvent(alice, "my-post", "First", createdAt = 100), null)

        articles.absorb(articleEvent(alice, "my-post", "Revised", createdAt = 200), null)

        assertEquals(1, articles.all.value.size)
        assertEquals("Revised", articles.all.value.values.single().title)
    }

    @Test
    fun `an older revision arriving late does not overwrite the newer one`() {
        val articles = repo()
        articles.absorb(articleEvent(alice, "my-post", "Revised", createdAt = 200), null)

        articles.absorb(articleEvent(alice, "my-post", "First", createdAt = 100), null)

        assertEquals("Revised", articles.all.value.values.single().title)
    }

    @Test
    fun `the same revision from a second relay only adds provenance`() {
        val articles = repo()
        articles.absorb(articleEvent(alice, "my-post", "First", createdAt = 100), relay("a.example"))

        articles.absorb(articleEvent(alice, "my-post", "First", createdAt = 100), relay("b.example"))

        val stored = articles.all.value.values.single()
        assertEquals(setOf(relay("a.example"), relay("b.example")), stored.seenOn)
    }

    @Test
    fun `two d tags from one author are two articles`() {
        val articles = repo()

        articles.absorb(articleEvent(alice, "first", "First", createdAt = 100), null)
        articles.absorb(articleEvent(alice, "second", "Second", createdAt = 100, idSeed = 2), null)

        assertEquals(2, articles.all.value.size)
    }

    @Test
    fun `an event that fails verification is dropped`() {
        val articles = repo(codec = FakeCodec(verifies = false))

        articles.absorb(articleEvent(alice, "my-post", "First", createdAt = 100), null)

        assertTrue(articles.all.value.isEmpty())
    }

    @Test
    fun `a kind that is not long-form is ignored`() {
        val articles = repo()
        val note = articleEvent(alice, "my-post", "First", createdAt = 100).copy(kind = 1)

        assertNull(articles.absorb(note, null))
    }

    @Test
    fun `publishing without a d tag mints one from the title`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("write.example"), read = false, write = true)
            val articles = repo(transport = transport, directory = directory)

            val result =
                articles.publish(
                    signer = FakeSigner(alice),
                    author = alice,
                    draft = ArticleDraft(title = "Hello There", summary = "", image = "", content = "body"),
                )

            assertTrue(result is PublishResult.Success)
            val published = transport.published.single().first
            assertEquals(listOf("hello-there-${clock.now}"), published.tagValues("d"))
        }

    @Test
    fun `editing keeps the d tag so the article is replaced, not duplicated`() =
        runTest {
            val transport = FakeTransport()
            val directory = RelayDirectory(clock)
            directory.approve(relay("write.example"), read = false, write = true)
            val articles = repo(transport = transport, directory = directory)

            articles.publish(
                signer = FakeSigner(alice),
                author = alice,
                draft = ArticleDraft("Hello There", "", "", "body", dTag = "existing-slug"),
            )

            assertEquals(listOf("existing-slug"), transport.published.single().first.tagValues("d"))
        }

    @Test
    fun `a watch-only account cannot publish an article`() =
        runTest {
            val articles = repo()

            val result =
                articles.publish(
                    signer = FakeSigner(alice, canSign = false),
                    author = alice,
                    draft = ArticleDraft("Title", "", "", "body"),
                )

            assertTrue(result is PublishResult.Failure)
        }

    @Test
    fun `slugs are stable, lowercase and free of punctuation`() {
        assertEquals("hello-there-5", ArticleRepository.slug("Hello, There!", now = 5))
        assertEquals("a-b-5", ArticleRepository.slug("  a   b  ", now = 5))
        // A title with nothing usable in it still yields a valid address.
        assertEquals("article-5", ArticleRepository.slug("!!!", now = 5))
        assertNotEquals(ArticleRepository.slug("Same", now = 1), ArticleRepository.slug("Same", now = 2))
    }
}
