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
}
