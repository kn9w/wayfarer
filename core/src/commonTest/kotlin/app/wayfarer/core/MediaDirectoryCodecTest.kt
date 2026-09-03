package app.wayfarer.core

import app.wayfarer.core.model.MediaDirectorySnapshot
import app.wayfarer.core.model.MediaGrant
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.MediaReason
import app.wayfarer.core.model.MediaSource
import app.wayfarer.core.model.PendingMediaHost
import app.wayfarer.core.store.MediaDirectoryCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaDirectoryCodecTest {
    private val codec = MediaDirectoryCodec()

    private fun host(name: String) = MediaHost(name)

    @Test
    fun `round-trips grants, queued hosts and denials`() {
        val snapshot =
            MediaDirectorySnapshot(
                grants = mapOf(host("pics.example") to MediaGrant(host("pics.example"), load = true)),
                pending =
                    mapOf(
                        host("maybe.example") to
                            PendingMediaHost(
                                host = host("maybe.example"),
                                reasons = setOf(MediaReason(MediaSource.AVATAR, "avatar of npub1abc")),
                                firstSeenAt = 100,
                                lastSeenAt = 200,
                            ),
                    ),
                denied = setOf(host("no.example")),
            )

        assertEquals(snapshot, codec.decode(codec.encode(snapshot)))
    }

    @Test
    fun `an empty or blank document decodes to an empty snapshot`() {
        assertEquals(MediaDirectorySnapshot(), codec.decode(null))
        assertEquals(MediaDirectorySnapshot(), codec.decode(""))
        assertEquals(MediaDirectorySnapshot(), codec.decode("   \n  "))
    }

    @Test
    fun `unparseable lines are skipped rather than failing the load`() {
        val text =
            """
            M	pics.example	true
            this line is nonsense
            M	broken.example	notabool
            M	http://scheme.example	true
            X	no.example
            """.trimIndent()

        val snapshot = codec.decode(text)

        assertEquals(setOf(host("pics.example")), snapshot.grants.keys)
        assertEquals(setOf(host("no.example")), snapshot.denied)
    }

    @Test
    fun `a grant wins over a stale queued or denied record for the same host`() {
        val text =
            """
            M	pics.example	true
            Q	pics.example	100	200	AVATAR:avatar of npub1abc
            X	pics.example
            """.trimIndent()

        val snapshot = codec.decode(text)

        assertTrue(snapshot.grants.containsKey(host("pics.example")))
        assertNull(snapshot.pending[host("pics.example")])
        assertFalse(host("pics.example") in snapshot.denied)
    }

    @Test
    fun `a revoked grant is not written back as an approved one`() {
        // encode never emits load=false, but a hand-edited file might.
        assertEquals(MediaDirectorySnapshot(), codec.decode("M\tpics.example\tfalse"))
    }

    @Test
    fun `a reason with no detail round-trips as no detail`() {
        val snapshot =
            MediaDirectorySnapshot(
                pending =
                    mapOf(
                        host("maybe.example") to
                            PendingMediaHost(
                                host = host("maybe.example"),
                                reasons = setOf(MediaReason(MediaSource.USER_ENTERED)),
                                firstSeenAt = 5,
                                lastSeenAt = 5,
                            ),
                    ),
            )

        assertEquals(snapshot, codec.decode(codec.encode(snapshot)))
    }

    @Test
    fun `a relay directory file decodes to an empty media snapshot`() {
        // The two formats use different record letters precisely so that reading
        // one as the other yields nothing at all, rather than a relay grant
        // quietly becoming permission to fetch pictures from that host.
        val relayFile =
            """
            G	wss://relay.example/	true	true
            P	wss://other.example/	100	200	AUTHOR_RELAY_LIST:write relay of abc
            D	wss://no.example/
            F	wss://relay.example/
            """.trimIndent()

        assertEquals(MediaDirectorySnapshot(), codec.decode(relayFile))
    }
}

class MediaHostTest {
    @Test
    fun `a plain host parses`() {
        assertEquals(MediaHost("image.nostr.build"), MediaHost.parseOrNull("image.nostr.build"))
    }

    @Test
    fun `case and surrounding space are normalized away`() {
        assertEquals(MediaHost("image.nostr.build"), MediaHost.parseOrNull("  Image.Nostr.Build  "))
    }

    @Test
    fun `a trailing root dot is dropped so one server is not two entries`() {
        assertEquals(MediaHost("image.nostr.build"), MediaHost.parseOrNull("image.nostr.build."))
    }

    @Test
    fun `anything that is not a bare host is refused`() {
        // Every one of these is a string that could make a grant for one server
        // authorise a request to another, so the parser takes none of them.
        for (raw in
            listOf(
                "",
                "   ",
                "https://image.nostr.build",
                "image.nostr.build/avatar.jpg",
                "evil.example@image.nostr.build",
                "image.nostr.build:8443",
                "image nostr build",
                "[2001:db8::1]",
                ".leading.dot",
                "double..dot",
                "under_score.example",
            )
        ) {
            assertNull(MediaHost.parseOrNull(raw), "expected \"$raw\" to be refused")
        }
    }

    @Test
    fun `null is refused`() {
        assertNull(MediaHost.parseOrNull(null))
    }
}
