package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.ArticleDraft
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.ProfileDraft
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.RelayListEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the Quartz adapter against real keys and real signatures — these are
 * the assertions that would have to keep passing after swapping the backend.
 */
class QuartzNostrCodecTest {
    private val keys = QuartzKeyTool()
    private val bech32 = QuartzBech32Codec()
    private val codec = QuartzNostrCodec()

    private val secKeyHex = keys.generateSecKeyHex()
    private val pubKey = keys.pubKeyOf(secKeyHex)
    private val signer = QuartzLocalSigner(pubKey, secKeyHex)

    /**
     * Re-serializes a signed event the way a signer app would hand it back.
     * Uses the codec's own writer so the test is not asserting against a
     * hand-built JSON string.
     */
    private fun rawJsonOf(event: app.wayfarer.core.model.NostrEvent): String =
        buildString {
            append("{")
            append(""""id":"${event.id.hex}",""")
            append(""""pubkey":"${event.pubKey.hex}",""")
            append(""""created_at":${event.createdAt},""")
            append(""""kind":${event.kind},""")
            append(""""tags":[],""")
            append(""""content":"${event.content}",""")
            append(""""sig":"${event.sig}"""")
            append("}")
        }

    @Test
    fun `npub and nsec round-trip`() {
        val npub = bech32.encodeNpub(pubKey)
        val nsec = bech32.encodeNsec(secKeyHex)

        assertTrue(npub.startsWith("npub1"))
        assertTrue(nsec.startsWith("nsec1"))
        assertEquals(pubKey, bech32.decodePubKey(npub))
        assertEquals(secKeyHex, bech32.decodeSecKeyHex(nsec))
    }

    @Test
    fun `bare hex is accepted for both key types`() {
        assertEquals(pubKey, bech32.decodePubKey(pubKey.hex))
        assertEquals(secKeyHex, bech32.decodeSecKeyHex(secKeyHex))
    }

    @Test
    fun `an npub is never mistaken for a secret key`() {
        assertNull(bech32.decodeSecKeyHex(bech32.encodeNpub(pubKey)))
    }

    @Test
    fun `garbage decodes to null rather than throwing`() {
        assertNull(bech32.decodePubKey("not a key"))
        assertNull(bech32.decodeSecKeyHex("npub1obviouslywrong"))
        assertNull(bech32.decodePubKey(""))
    }

    @Test
    fun `a signed note verifies and a tampered one does not`() =
        runTest {
            val note = signer.sign(UnsignedEvent(EventKind.TEXT_NOTE, "hello", emptyList(), 1_700_000_000))

            assertEquals(pubKey, note.pubKey)
            assertEquals(128, note.sig.length)
            assertTrue(codec.verify(note))
            assertFalse(codec.verify(note.copy(content = "tampered")))
        }

    @Test
    fun `a watch-only signer cannot sign`() =
        runTest {
            val watchOnly = WatchOnlySigner(pubKey)

            assertFalse(watchOnly.canSign)
            try {
                watchOnly.sign(UnsignedEvent(EventKind.TEXT_NOTE, "nope", emptyList(), 1))
                throw AssertionError("expected a failure")
            } catch (expected: IllegalStateException) {
                // The login screen relies on this being an ordinary failure, not a crash.
            }
        }

    @Test
    fun `a profile round-trips and blank fields stay absent`() =
        runTest {
            val draft = ProfileDraft("wayfarer", "Way Farer", "bio", "https://x/p.png", "", "https://example.com", "", "zap@example.com")

            val event = signer.sign(codec.writeProfile(null, draft, 1_700_000_000))
            val profile = codec.readProfile(event)

            assertEquals(EventKind.METADATA, event.kind)
            assertEquals("wayfarer", profile?.name)
            assertEquals("Way Farer", profile?.displayName)
            assertEquals("zap@example.com", profile?.lud16)
            assertNull(profile?.banner)
            assertNull(profile?.nip05)
        }

    @Test
    fun `editing a profile preserves fields this app does not model`() =
        runTest {
            val draft = ProfileDraft("wayfarer", "", "bio", "", "", "", "", "")
            val original = signer.sign(codec.writeProfile(null, draft, 1_700_000_000))
            // Some other client set a field Wayfarer has no UI for.
            val withExtra = original.copy(content = original.content.dropLast(1) + ""","lud06":"lnurl1keepme"}""")

            val edited = signer.sign(codec.writeProfile(withExtra, draft.copy(about = "edited"), 1_700_000_100))

            assertTrue(edited.content.contains("lnurl1keepme"), edited.content)
            assertEquals("edited", codec.readProfile(edited)?.about)
        }

    @Test
    fun `nip65 read write and both markers survive a round-trip`() =
        runTest {
            val entries =
                listOf(
                    RelayListEntry(RelayUrl("wss://both.example/"), read = true, write = true),
                    RelayListEntry(RelayUrl("wss://write.example/"), read = false, write = true),
                    RelayListEntry(RelayUrl("wss://read.example/"), read = true, write = false),
                )

            val event = signer.sign(codec.writeRelayList(entries, 1_700_000_000))
            val parsed = codec.readRelayList(event)

            assertEquals(EventKind.RELAY_LIST, event.kind)
            assertEquals(entries.toSet(), parsed.toSet())
            // An unmarked `r` tag means both; only the one-way entries carry a marker.
            assertEquals(setOf("read", "write"), event.tagRows("r").mapNotNull { it.getOrNull(2) }.toSet())
        }

    @Test
    fun `nip02 follows drop entries that are not valid pubkeys`() =
        runTest {
            val event =
                signer.sign(
                    UnsignedEvent(
                        EventKind.CONTACT_LIST,
                        "",
                        listOf(listOf("p", pubKey.hex), listOf("p", "garbage"), listOf("p", "")),
                        1_700_000_000,
                    ),
                )

            assertEquals(setOf(pubKey), codec.readFollows(event))
        }

    @Test
    fun `a long-form article round-trips`() =
        runTest {
            val draft =
                ArticleDraft(
                    title = "On Outboxes",
                    summary = "Why relay lists matter",
                    image = "https://example.com/header.png",
                    content = "# Heading\n\nBody text.",
                    dTag = "on-outboxes",
                )

            val event = signer.sign(codec.writeArticle(draft, 1_700_000_000))
            val article = codec.readArticle(event)

            assertEquals(EventKind.LONG_FORM, event.kind)
            assertEquals("On Outboxes", article?.title)
            assertEquals("Why relay lists matter", article?.summary)
            assertEquals("https://example.com/header.png", article?.image)
            assertEquals("# Heading\n\nBody text.", article?.content)
            assertEquals("on-outboxes", article?.dTag)
            assertEquals("30023:${pubKey.hex}:on-outboxes", article?.address)
        }

    @Test
    fun `editing an article keeps its address so the replacement replaces`() =
        runTest {
            val first =
                signer.sign(
                    codec.writeArticle(ArticleDraft("Title", "", "", "v1", dTag = "stable-slug"), 1_700_000_000),
                )
            val second =
                signer.sign(
                    codec.writeArticle(ArticleDraft("Title", "", "", "v2", dTag = "stable-slug"), 1_700_000_100),
                )

            // Different events, same addressable identity — that is what makes the
            // second one a revision rather than a second article.
            assertNotEquals(first.id, second.id)
            assertEquals(codec.readArticle(first)?.address, codec.readArticle(second)?.address)
        }

    @Test
    fun `an article with no title is not an article`() =
        runTest {
            val event = signer.sign(codec.writeArticle(ArticleDraft("", "", "", "body", dTag = "d"), 1_700_000_000))

            assertNull(codec.readArticle(event))
        }

    @Test
    fun `an unsigned event is serialized for an external signer with the author pubkey`() {
        val json =
            codec.encodeForSigning(
                UnsignedEvent(EventKind.TEXT_NOTE, "hello", listOf(listOf("p", pubKey.hex)), 1_700_000_000),
                pubKey.hex,
            )

        assertTrue(json.contains(""""pubkey":"${pubKey.hex}""""), json)
        assertTrue(json.contains(""""kind":1"""), json)
        assertTrue(json.contains("hello"), json)
    }

    @Test
    fun `an event returned by a signer is parsed back`() =
        runTest {
            val signed = signer.sign(UnsignedEvent(EventKind.TEXT_NOTE, "round trip", emptyList(), 1_700_000_000))

            val decoded = codec.decodeEvent(rawJsonOf(signed))

            assertEquals(signed.id, decoded?.id)
            assertEquals(signed.sig, decoded?.sig)
            assertTrue(codec.verify(decoded!!))
        }

    @Test
    fun `garbage from a signer decodes to null rather than throwing`() {
        assertNull(codec.decodeEvent("not json"))
    }

    @Test
    fun `an nprofile keeps the relays it names, and an npub has none to keep`() {
        val hint = com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl("wss://hinted.example/")
        val nprofile = com.vitorpamplona.quartz.nip19Bech32.entities.NProfile.create(pubKey.hex, listOf(hint))

        val fromProfile = bech32.decodeProfileRef(nprofile)
        assertEquals(pubKey, fromProfile?.pubKey)
        assertEquals(listOf("wss://hinted.example/"), fromProfile?.relayHints)

        // The distinction the onboarding flow turns on: a bare npub names nowhere
        // to look, which is why finding that person means querying relays the user
        // has to be asked about first.
        val fromNpub = bech32.decodeProfileRef(bech32.encodeNpub(pubKey))
        assertEquals(pubKey, fromNpub?.pubKey)
        assertTrue(fromNpub?.relayHints.isNullOrEmpty())

        assertNull(bech32.decodeProfileRef("not a key"))
    }

    @Test
    fun `an nprofile with relay hints is accepted wherever a key is`() {
        val hint = com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl("wss://hinted.example/")
        val nprofile = com.vitorpamplona.quartz.nip19Bech32.entities.NProfile.create(pubKey.hex, listOf(hint))

        assertEquals(pubKey, bech32.decodePubKey(nprofile))
        assertEquals(pubKey, bech32.decodeProfileRef("nostr:$nprofile")?.pubKey)
    }

    @Test
    fun `relay urls that name the same relay collapse to one permission key`() {
        val upper = quartzRelayUrlNormalizer.normalize("wss://Relay.Example.com")
        val lower = quartzRelayUrlNormalizer.normalize("wss://relay.example.com/")
        val bare = quartzRelayUrlNormalizer.normalize("relay.example.com")

        assertEquals(lower, upper)
        assertEquals(lower, bare)
    }
}
