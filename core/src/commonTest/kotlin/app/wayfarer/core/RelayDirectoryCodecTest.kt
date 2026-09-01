package app.wayfarer.core

import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.PendingRelay
import app.wayfarer.core.model.RelayDirectorySnapshot
import app.wayfarer.core.model.RelayGrant
import app.wayfarer.core.store.RelayDirectoryCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayDirectoryCodecTest {
    private val codec = RelayDirectoryCodec(testNormalizer)

    @Test
    fun `round-trips grants, pending entries and denials`() {
        val snapshot =
            RelayDirectorySnapshot(
                grants =
                    mapOf(
                        relay("read.example") to RelayGrant(relay("read.example"), read = true, write = false),
                        relay("both.example") to RelayGrant(relay("both.example"), read = true, write = true),
                    ),
                pending =
                    mapOf(
                        relay("maybe.example") to
                            PendingRelay(
                                url = relay("maybe.example"),
                                reasons = setOf(DiscoveryReason(DiscoverySource.AUTHOR_RELAY_LIST, "write relay of abc")),
                                firstSeenAt = 100,
                                lastSeenAt = 200,
                            ),
                    ),
                denied = setOf(relay("no.example")),
            )

        assertEquals(snapshot, codec.decode(codec.encode(snapshot)))
    }

    @Test
    fun `a pending entry with no detail round-trips`() {
        val snapshot =
            RelayDirectorySnapshot(
                pending =
                    mapOf(
                        relay("boot.example") to
                            PendingRelay(
                                url = relay("boot.example"),
                                reasons = setOf(DiscoveryReason(DiscoverySource.BOOTSTRAP)),
                                firstSeenAt = 1,
                                lastSeenAt = 1,
                            ),
                    ),
            )

        assertEquals(snapshot, codec.decode(codec.encode(snapshot)))
    }

    @Test
    fun `unparseable lines are skipped rather than failing the load`() {
        val decoded = codec.decode("G\twss://good.example/\ttrue\tfalse\nthis is not a record\nG\tnot-a-url")

        assertEquals(setOf(relay("good.example")), decoded.grants.keys)
    }

    @Test
    fun `empty input decodes to an empty directory`() {
        assertEquals(RelayDirectorySnapshot(), codec.decode(null))
        assertEquals(RelayDirectorySnapshot(), codec.decode(""))
    }

    @Test
    fun `a grant wins over a stale pending or denied record for the same relay`() {
        val decoded =
            codec.decode(
                "G\twss://a.example/\ttrue\ttrue\n" +
                    "P\twss://a.example/\t1\t2\tBOOTSTRAP:\n" +
                    "D\twss://a.example/\n",
            )

        assertTrue(relay("a.example") in decoded.grants)
        assertFalse(relay("a.example") in decoded.pending)
        assertFalse(relay("a.example") in decoded.denied)
    }

    // ---- favourites -------------------------------------------------------

    @Test
    fun `a starred relay survives the round trip`() {
        val codec = RelayDirectoryCodec(testNormalizer)
        val snapshot =
            RelayDirectorySnapshot(
                grants = mapOf(relay("a.example") to RelayGrant.readOnly(relay("a.example"))),
                favourites = setOf(relay("a.example"), relay("b.example")),
            )

        val decoded = codec.decode(codec.encode(snapshot))

        assertEquals(setOf(relay("a.example"), relay("b.example")), decoded.favourites)
    }

    @Test
    fun `a star outlives the grant it was put on`() {
        val codec = RelayDirectoryCodec(testNormalizer)
        // Revoking a relay deletes its grant. A star kept on the grant would go
        // with it and come back wrong; kept apart, it is still there.
        val snapshot = RelayDirectorySnapshot(grants = emptyMap(), favourites = setOf(relay("a.example")))

        assertEquals(setOf(relay("a.example")), codec.decode(codec.encode(snapshot)).favourites)
    }

    @Test
    fun `a file written before favourites existed still loads every grant`() {
        val codec = RelayDirectoryCodec(testNormalizer)
        // The upgrade case. Had the flag been a fifth column on G, this file
        // would have parsed to nothing and silently emptied the user's list.
        val old = "G\twss://a.example/\ttrue\tfalse\nD\twss://blocked.example/\n"

        val decoded = codec.decode(old)

        assertEquals(setOf(relay("a.example")), decoded.grants.keys)
        assertEquals(setOf(relay("blocked.example")), decoded.denied)
        assertTrue(decoded.favourites.isEmpty())
    }
}
