package app.wayfarer.core

import app.wayfarer.core.outbox.RelayCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayCoverageTest {
    @Test
    fun `picks the relay that covers the most authors first`() {
        val hub = relay("hub.example")
        val niche = relay("niche.example")

        val selection =
            RelayCoverage.select(
                candidatesByAuthor =
                    mapOf(
                        pubKey(1) to listOf(hub),
                        pubKey(2) to listOf(hub),
                        pubKey(3) to listOf(hub, niche),
                    ),
                redundancy = 1,
                maxRelays = 5,
            )

        assertEquals(setOf(hub), selection.assignments.keys)
        assertEquals(setOf(pubKey(1), pubKey(2), pubKey(3)), selection.assignments.getValue(hub))
        assertTrue(selection.uncovered.isEmpty())
    }

    @Test
    fun `each relay is only asked for the authors it is responsible for`() {
        val a = relay("a.example")
        val b = relay("b.example")

        val selection =
            RelayCoverage.select(
                candidatesByAuthor =
                    mapOf(
                        pubKey(1) to listOf(a),
                        pubKey(2) to listOf(b),
                    ),
                redundancy = 1,
                maxRelays = 5,
            )

        assertEquals(setOf(pubKey(1)), selection.assignments.getValue(a))
        assertEquals(setOf(pubKey(2)), selection.assignments.getValue(b))
    }

    @Test
    fun `redundancy of two uses a second relay for the same author`() {
        val a = relay("a.example")
        val b = relay("b.example")

        val selection =
            RelayCoverage.select(
                candidatesByAuthor = mapOf(pubKey(1) to listOf(a, b)),
                redundancy = 2,
                maxRelays = 5,
            )

        assertEquals(setOf(a, b), selection.assignments.keys)
    }

    @Test
    fun `an author advertising one relay does not loop when redundancy is higher`() {
        val only = relay("only.example")

        val selection =
            RelayCoverage.select(
                candidatesByAuthor = mapOf(pubKey(1) to listOf(only)),
                redundancy = 3,
                maxRelays = 10,
            )

        assertEquals(setOf(only), selection.assignments.keys)
        assertTrue(selection.uncovered.isEmpty())
    }

    @Test
    fun `authors with no approved relay are reported instead of dropped`() {
        val selection =
            RelayCoverage.select(
                candidatesByAuthor = mapOf(pubKey(7) to emptyList()),
                redundancy = 1,
                maxRelays = 5,
            )

        assertTrue(selection.assignments.isEmpty())
        assertEquals(setOf(pubKey(7)), selection.uncovered)
    }

    @Test
    fun `the relay budget is never exceeded`() {
        val candidates = (1..20).associate { pubKey(it) to listOf(relay("r$it.example")) }

        val selection = RelayCoverage.select(candidates, redundancy = 1, maxRelays = 4)

        assertEquals(4, selection.assignments.size)
        assertEquals(16, selection.uncovered.size)
    }

    @Test
    fun `an already-connected relay wins a tie`() {
        val cold = relay("aaa.example")
        val warm = relay("zzz.example")

        val selection =
            RelayCoverage.select(
                candidatesByAuthor = mapOf(pubKey(1) to listOf(cold, warm)),
                redundancy = 1,
                maxRelays = 1,
                preferred = setOf(warm),
            )

        assertEquals(setOf(warm), selection.assignments.keys)
    }
}
