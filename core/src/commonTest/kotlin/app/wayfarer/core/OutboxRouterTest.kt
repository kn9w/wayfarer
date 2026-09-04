package app.wayfarer.core

import app.wayfarer.core.nostr.RelayListEntry
import app.wayfarer.core.outbox.OutboxConfig
import app.wayfarer.core.outbox.OutboxRouter
import app.wayfarer.core.outbox.RelayList
import app.wayfarer.core.outbox.RelayListCache
import app.wayfarer.core.relay.RelayDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxRouterTest {
    private val alice = pubKey(1)
    private val bob = pubKey(2)

    private val aliceOutbox = relay("alice-write.example")
    private val bobOutbox = relay("bob-write.example")
    private val bobInbox = relay("bob-read.example")

    private fun cacheWith(vararg lists: RelayList) = RelayListCache().apply { lists.forEach { put(it) } }

    private fun aliceList() =
        RelayList(
            alice,
            createdAt = 10,
            entries = listOf(RelayListEntry(aliceOutbox, read = false, write = true)),
        )

    private fun bobList() =
        RelayList(
            bob,
            createdAt = 10,
            entries =
                listOf(
                    RelayListEntry(bobOutbox, read = false, write = true),
                    RelayListEntry(bobInbox, read = true, write = false),
                ),
        )

    @Test
    fun `notes are read from each author's own write relays`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            directory.approve(aliceOutbox, read = true, write = false)
            directory.approve(bobOutbox, read = true, write = false)
            val router = OutboxRouter(cacheWith(aliceList(), bobList()), directory)

            val plan = router.readPlanFor(setOf(alice, bob), kinds = listOf(1))

            assertEquals(setOf(aliceOutbox, bobOutbox), plan.plan.keys)
            assertEquals(listOf(alice.hex), plan.plan.getValue(aliceOutbox).single().authors)
            assertEquals(listOf(bob.hex), plan.plan.getValue(bobOutbox).single().authors)
        }

    @Test
    fun `an author whose relays are unapproved is reported unreachable, not silently dropped`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            directory.approve(aliceOutbox, read = true, write = false)
            val router = OutboxRouter(cacheWith(aliceList(), bobList()), directory)

            val plan = router.readPlanFor(setOf(alice, bob), kinds = listOf(1))

            assertEquals(setOf(bob), plan.unreachable)
            assertFalse(bobOutbox in plan.plan)
        }

    @Test
    fun `the unapproved relay of an unreachable author is queued for approval`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            directory.approve(aliceOutbox, read = true, write = false)
            val router = OutboxRouter(cacheWith(aliceList(), bobList()), directory)

            router.readPlanFor(setOf(alice, bob), kinds = listOf(1))

            assertTrue(bobOutbox in directory.pending)
        }

    @Test
    fun `publishing a mention also targets the mentioned user's read relays`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            directory.approve(aliceOutbox, read = false, write = true)
            directory.approve(bobInbox, read = false, write = true)
            val router = OutboxRouter(cacheWith(aliceList(), bobList()), directory)

            val plan = router.publishPlanFor(alice, mentions = setOf(bob))

            assertEquals(setOf(aliceOutbox), plan.ownWrite)
            assertEquals(setOf(bobInbox), plan.mentionInbox)
            assertEquals(setOf(aliceOutbox, bobInbox), plan.relays)
        }

    @Test
    fun `a mention inbox that is not approved is left out and queued`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            directory.approve(aliceOutbox, read = false, write = true)
            val router = OutboxRouter(cacheWith(aliceList(), bobList()), directory)

            val plan = router.publishPlanFor(alice, mentions = setOf(bob))

            assertEquals(setOf(aliceOutbox), plan.relays)
            assertTrue(bobInbox in directory.pending)
        }

    @Test
    fun `with no relay list yet, publishing falls back to every approved write relay`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val fallback = relay("fallback.example")
            directory.approve(fallback, read = true, write = true)
            val router = OutboxRouter(RelayListCache(), directory)

            val plan = router.publishPlanFor(alice)

            assertEquals(setOf(fallback), plan.relays)
        }

    @Test
    fun `publishing with nothing approved yields an empty plan rather than a broadcast`() =
        runTest {
            val router = OutboxRouter(cacheWith(aliceList()), RelayDirectory(FakeClock()))

            assertTrue(router.publishPlanFor(alice).isEmpty)
        }

    @Test
    fun `an author with no relay list falls back to the approved read relays`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val fallback = relay("mine.example")
            directory.approve(fallback, read = true, write = false)
            val router = OutboxRouter(RelayListCache(), directory)

            val plan = router.readPlanFor(setOf(alice), kinds = listOf(1))

            assertEquals(setOf(fallback), plan.plan.keys)
            assertEquals(setOf(alice), plan.guessed)
            assertTrue(plan.unreachable.isEmpty())
        }

    @Test
    fun `strict mode reports an author with no relay list as unreachable`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            directory.approve(relay("mine.example"), read = true, write = false)
            val router = OutboxRouter(RelayListCache(), directory, OutboxConfig(fallbackToApprovedReadRelays = false))

            val plan = router.readPlanFor(setOf(alice), kinds = listOf(1))

            assertTrue(plan.plan.isEmpty())
            assertEquals(setOf(alice), plan.unreachable)
            assertTrue(plan.guessed.isEmpty())
        }

    @Test
    fun `a guessed author is never reported as both guessed and unreachable`() =
        runTest {
            // Nothing approved at all: the fallback is empty, so this is a miss.
            val router = OutboxRouter(RelayListCache(), RelayDirectory(FakeClock()))

            val plan = router.readPlanFor(setOf(alice), kinds = listOf(1))

            assertEquals(setOf(alice), plan.unreachable)
            assertTrue(plan.guessed.isEmpty())
        }

    @Test
    fun `browsing a named relay still goes through the permission gate`() =
        runTest {
            val allowed = relay("allowed.example")
            val notAllowed = relay("not-allowed.example")
            val directory = RelayDirectory(FakeClock())
            directory.approve(allowed, read = true, write = false)
            val router = OutboxRouter(RelayListCache(), directory)

            val plan = router.relayPlanFor(listOf(allowed, notAllowed), kinds = listOf(1), limitPerRelay = 20)

            // "The user asked for this relay" is not a way around the gate: the
            // unapproved one is filed for a decision rather than queried.
            assertEquals(setOf(allowed), plan.keys)
            assertTrue(notAllowed in directory.pending)
            assertEquals(null, plan.getValue(allowed).single().authors)
        }

    @Test
    fun `browsing with nothing approved plans nothing`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val router = OutboxRouter(RelayListCache(), directory)

            assertTrue(router.relayPlanFor(listOf(relay("somewhere.example")), kinds = listOf(1)).isEmpty())
        }

    @Test
    fun `the read plan respects the relay budget`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            val cache = RelayListCache()
            repeat(10) { index ->
                val author = pubKey(index + 10)
                val url = relay("r$index.example")
                directory.approve(url, read = true, write = false)
                cache.put(RelayList(author, createdAt = 1, entries = listOf(RelayListEntry(url, read = false, write = true))))
            }
            val router = OutboxRouter(cache, directory, OutboxConfig(readRedundancy = 1, maxReadRelays = 3))

            val plan = router.readPlanFor((10..19).mapTo(mutableSetOf()) { pubKey(it) }, kinds = listOf(1))

            assertEquals(3, plan.plan.size)
        }

    @Test
    fun `discovery does not hand every relay the whole follow list`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            repeat(6) { directory.approve(relay("d$it.example"), read = true, write = false) }
            val router = OutboxRouter(RelayListCache(), directory)

            val authors = (10..49).mapTo(mutableSetOf()) { pubKey(it) }
            val plan = router.discoveryPlanFor(authors, kinds = listOf(0))

            // Capped, so approving more relays widens the reach of a query
            // without widening what any one of them is told.
            assertEquals(4, plan.size)
            for ((relayUrl, filters) in plan) {
                val asked = filters.single().authors.orEmpty()
                assertTrue(
                    asked.size < authors.size,
                    "$relayUrl was handed the whole follow list, which is what sharding exists to prevent",
                )
            }
        }

    @Test
    fun `every author discovery asks about reaches more than one relay`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            repeat(4) { directory.approve(relay("d$it.example"), read = true, write = false) }
            val router = OutboxRouter(RelayListCache(), directory)

            val authors = (10..29).mapTo(mutableSetOf()) { pubKey(it) }
            val plan = router.discoveryPlanFor(authors, kinds = listOf(0))

            // Sharding must not become a single point of failure: a relay that
            // has never heard of somebody would otherwise lose them outright.
            for (author in authors) {
                val serving = plan.values.count { author.hex in it.single().authors.orEmpty() }
                assertEquals(2, serving, "${author.abbreviated()} was asked of $serving relays")
            }
        }

    @Test
    fun `discovery with one approved relay still asks it for everybody`() =
        runTest {
            val directory = RelayDirectory(FakeClock())
            directory.approve(relay("only.example"), read = true, write = false)
            val router = OutboxRouter(RelayListCache(), directory)

            val plan = router.discoveryPlanFor(setOf(alice, bob), kinds = listOf(0))

            // Nothing to spread across. Sharding is a gain where there are relays
            // to shard over, never a reason to fetch less than was asked for.
            assertEquals(1, plan.size)
            assertEquals(setOf(alice.hex, bob.hex), plan.values.single().single().authors.orEmpty().toSet())
        }
}
