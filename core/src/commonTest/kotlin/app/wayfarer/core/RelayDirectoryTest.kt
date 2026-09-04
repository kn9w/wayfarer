package app.wayfarer.core

import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.relay.RelayAccessPolicy
import app.wayfarer.core.relay.RelayDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayDirectoryTest {
    private val reason = DiscoveryReason(DiscoverySource.AUTHOR_RELAY_LIST, "write relay of someone")

    @Test
    fun `nothing is approved by default`() =
        runTest {
            val directory = RelayDirectory(FakeClock())

            assertFalse(directory.isApproved(relay("a.example")))
            assertEquals(emptySet(), directory.readable(listOf(relay("a.example")), reason))
            assertEquals(emptySet(), directory.writable(listOf(relay("a.example")), reason))
        }

    @Test
    fun `an unapproved relay lands in pending with the reason it was wanted`() =
        runTest {
            val directory = RelayDirectory(FakeClock())

            directory.readable(listOf(relay("a.example")), reason)

            val pending = directory.pending.getValue(relay("a.example"))
            assertEquals(setOf(reason), pending.reasons)
        }

    @Test
    fun `read and write are granted independently`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val url = relay("a.example")

            directory.approve(url, read = true, write = false)

            assertEquals(setOf(url), directory.readable(listOf(url), reason))
            assertEquals(emptySet(), directory.writable(listOf(url), reason))
        }

    @Test
    fun `approving clears the pending entry`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val url = relay("a.example")
            directory.readable(listOf(url), reason)
            assertTrue(url in directory.pending)

            directory.approve(url, read = true, write = true)

            assertFalse(url in directory.pending)
            assertTrue(directory.isApproved(url))
        }

    @Test
    fun `a denied relay does not come back as pending`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val url = relay("a.example")
            directory.deny(url)

            directory.readable(listOf(url), reason)

            assertFalse(url in directory.pending)
            assertFalse(directory.isApproved(url))
        }

    @Test
    fun `a relay granted read only is not re-queued when write is refused`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val url = relay("a.example")
            directory.approve(url, read = true, write = false)

            directory.writable(listOf(url), reason)

            // The user already decided about this relay; asking again would be noise.
            assertFalse(url in directory.pending)
        }

    @Test
    fun `revoking both permissions removes the grant`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val url = relay("a.example")
            directory.approve(url, read = true, write = true)

            directory.approve(url, read = false, write = false)

            assertFalse(directory.isApproved(url))
            assertFalse(url in directory.grants)
        }

    @Test
    fun `bootstrap suggestions are queued, never approved`() =
        runTest {
            val directory = RelayDirectory(FakeClock())

            directory.suggest(listOf(relay("suggested.example")))

            assertTrue(relay("suggested.example") in directory.pending)
            assertFalse(directory.isApproved(relay("suggested.example")))
        }

    @Test
    fun `the policy view answers each direction separately`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val readOnly = relay("read-only.example")
            val writeOnly = relay("write-only.example")

            directory.approve(readOnly, read = true, write = false)
            directory.approve(writeOnly, read = false, write = true)

            // Seen through the interface the transport actually holds, because
            // that is where the distinction has to survive.
            val policy: RelayAccessPolicy = directory

            // Both are approved for *something*, which is what a socket needs to
            // know and all it needs to know.
            assertTrue(policy.isApproved(readOnly))
            assertTrue(policy.isApproved(writeOnly))

            assertTrue(policy.canRead(readOnly))
            assertFalse(policy.canWrite(readOnly), "\"Get posts\" is not \"and send mine\"")

            assertFalse(policy.canRead(writeOnly))
            assertTrue(policy.canWrite(writeOnly))
        }

    @Test
    fun `a relay with no grant answers no to every question`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val policy: RelayAccessPolicy = directory
            val unknown = relay("unknown.example")

            assertFalse(policy.isApproved(unknown))
            assertFalse(policy.canRead(unknown))
            assertFalse(policy.canWrite(unknown))
        }
}
