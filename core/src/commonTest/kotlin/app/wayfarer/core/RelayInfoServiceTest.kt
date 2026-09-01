package app.wayfarer.core

import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayInfo
import app.wayfarer.core.nostr.RelayInfoFetcher
import app.wayfarer.core.relay.RelayInfoService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayInfoServiceTest {
    private val url = relay("info.example")

    private class CountingFetcher(
        private val fail: Boolean = false,
    ) : RelayInfoFetcher {
        var calls = 0

        override suspend fun fetch(url: RelayUrl): RelayInfo {
            calls++
            if (fail) throw IllegalStateException("relay is down")
            return RelayInfo(
                url = url,
                name = "Example Relay",
                description = null,
                software = "strfry",
                version = "1.0",
                supportedNips = listOf(1, 11, 65),
                authRequired = false,
                paymentRequired = true,
                maxMessageLength = 65536,
                postingPolicy = null,
                paymentsUrl = null,
            )
        }
    }

    @Test
    fun `nothing is fetched until the user asks`() =
        runTest {
            val fetcher = CountingFetcher()
            val service = RelayInfoService(fetcher)

            // Constructing the service, and reading it, must not touch the network.
            assertEquals(null, service[url])
            assertEquals(0, fetcher.calls)
        }

    @Test
    fun `a user request fetches once and caches the answer`() =
        runTest {
            val fetcher = CountingFetcher()
            val service = RelayInfoService(fetcher)

            val first = service.fetchOnUserRequest(url)
            val second = service.fetchOnUserRequest(url)

            assertEquals(1, fetcher.calls)
            assertEquals(first, second)
            assertTrue(first is RelayInfoService.Entry.Loaded)
        }

    @Test
    fun `forcing a refetch is another explicit request`() =
        runTest {
            val fetcher = CountingFetcher()
            val service = RelayInfoService(fetcher)

            service.fetchOnUserRequest(url)
            service.fetchOnUserRequest(url, force = true)

            assertEquals(2, fetcher.calls)
        }

    @Test
    fun `the parsed document keeps the fields the approval decision turns on`() =
        runTest {
            val service = RelayInfoService(CountingFetcher())

            val entry = service.fetchOnUserRequest(url) as RelayInfoService.Entry.Loaded

            assertEquals("Example Relay", entry.info.name)
            assertEquals(listOf(1, 11, 65), entry.info.supportedNips)
            assertTrue(entry.info.paymentRequired)
        }

    @Test
    fun `a failure is recorded rather than thrown at the caller`() =
        runTest {
            val service = RelayInfoService(CountingFetcher(fail = true))

            val entry = service.fetchOnUserRequest(url)

            assertTrue(entry is RelayInfoService.Entry.Failed)
            assertEquals("relay is down", entry.message)
        }

    @Test
    fun `clearing forgets what was known`() =
        runTest {
            val service = RelayInfoService(CountingFetcher())
            service.fetchOnUserRequest(url)

            service.clear(url)

            assertEquals(null, service[url])
        }
}
