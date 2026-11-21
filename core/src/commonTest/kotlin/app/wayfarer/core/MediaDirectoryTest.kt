package app.wayfarer.core

import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.MediaReason
import app.wayfarer.core.model.MediaSource
import app.wayfarer.core.relay.MediaDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaDirectoryTest {
    private val reason = MediaReason(MediaSource.AVATAR, "avatar of npub1abc")

    private fun host(name: String) = MediaHost(name)

    @Test
    fun `nothing is approved by default`() =
        runTest {
            val directory = MediaDirectory(FakeClock())

            assertFalse(directory.isApproved(host("pics.example")))
            assertEquals(emptySet(), directory.loadable(listOf(host("pics.example")), reason))
        }

    @Test
    fun `an unasked host lands in the queue with the reason it was wanted`() =
        runTest {
            val directory = MediaDirectory(FakeClock())

            directory.loadable(listOf(host("pics.example")), reason)

            assertEquals(setOf(reason), directory.pending.getValue(host("pics.example")).reasons)
        }

    @Test
    fun `approving clears the queue entry`() =
        runTest {
            val directory = MediaDirectory(FakeClock())
            val url = host("pics.example")
            directory.loadable(listOf(url), reason)

            directory.approve(url, load = true)

            assertTrue(directory.isApproved(url))
            assertEquals(setOf(url), directory.loadable(listOf(url), reason))
            assertNull(directory.pending[url])
        }

    @Test
    fun `a denied host does not come back as pending`() =
        runTest {
            val directory = MediaDirectory(FakeClock())
            val url = host("pics.example")
            directory.deny(url)

            directory.loadable(listOf(url), reason)

            assertNull(directory.pending[url])
            assertFalse(directory.isApproved(url))
        }

    @Test
    fun `revoking removes the grant`() =
        runTest {
            val directory = MediaDirectory(FakeClock())
            val url = host("pics.example")
            directory.approve(url, load = true)

            directory.approve(url, load = false)

            assertFalse(directory.isApproved(url))
            assertNull(directory.grants[url])
        }

    @Test
    fun `a revoked host is queued again when something asks for it`() =
        runTest {
            val directory = MediaDirectory(FakeClock())
            val url = host("pics.example")
            directory.approve(url, load = true)
            directory.approve(url, load = false)

            directory.loadable(listOf(url), reason)

            // Revocation is not denial: the host has no decision recorded, so
            // the next profile that names it puts it back in front of the user.
            assertEquals(setOf(reason), directory.pending.getValue(url).reasons)
        }

    @Test
    fun `forgetting a denied host lets it be queued again`() =
        runTest {
            val directory = MediaDirectory(FakeClock())
            val url = host("pics.example")
            directory.deny(url)

            directory.forget(url)
            directory.loadable(listOf(url), reason)

            assertEquals(setOf(reason), directory.pending.getValue(url).reasons)
        }

    @Test
    fun `reasons accumulate for a host wanted more than once`() =
        runTest {
            val clock = FakeClock()
            val directory = MediaDirectory(clock)
            val url = host("pics.example")
            val banner = MediaReason(MediaSource.BANNER, "banner of npub1def")

            directory.loadable(listOf(url), reason)
            clock.now += 60
            directory.loadable(listOf(url), banner)

            val pending = directory.pending.getValue(url)
            assertEquals(setOf(reason, banner), pending.reasons)
            assertTrue(pending.lastSeenAt > pending.firstSeenAt)
        }

    @Test
    fun `nothing is suggested at first run`() =
        runTest {
            // The inverse of the relay directory's bootstrap behaviour, and
            // deliberately so: with no approved media host the app still works,
            // so shipping a list of image servers would be inventing one on the
            // user's behalf.
            val directory = MediaDirectory(FakeClock())

            assertEquals(emptyMap(), directory.grants)
            assertEquals(emptyMap(), directory.pending)
        }
}
