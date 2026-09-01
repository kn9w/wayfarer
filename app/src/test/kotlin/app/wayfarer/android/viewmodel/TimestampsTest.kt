package app.wayfarer.android.viewmodel

import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimestampsTest {
    private val utc = ZoneId.of("UTC")
    private val now = 1_700_000_000L
    private val minute = 60L
    private val hour = 60 * minute
    private val day = 24 * hour

    // Zone and locale pinned so the assertions describe the formatter rather
    // than the machine the tests happen to run on.
    private fun at(age: Long) = formatTimestamp(now - age, now, utc, Locale.UK)

    @Test
    fun `something just posted is now`() {
        assertEquals("now", at(0))
        assertEquals("now", at(59))
    }

    @Test
    fun `minutes, hours and days each take over at their boundary`() {
        assertEquals("1m", at(minute))
        assertEquals("59m", at(hour - 1))
        assertEquals("1h", at(hour))
        assertEquals("23h", at(day - 1))
        assertEquals("1d", at(day))
        assertEquals("6d", at(7 * day - 1))
    }

    @Test
    fun `past a week an age stops meaning anything and a date takes over`() {
        // "37w" answers no question anyone has; the calendar does.
        assertEquals("7 Nov 2023", at(7 * day))
        assertTrue(at(400 * day).endsWith("2022"))
    }

    @Test
    fun `a timestamp from the future reads as now rather than as negative`() {
        // Relays hand back whatever created_at an author's clock claimed, and
        // "-3h" reads as a bug in this app rather than as skew in theirs.
        assertEquals("now", at(-hour))
        assertEquals("now", at(-400 * day))
    }

    @Test
    fun `an npub is shortened at both ends so it stays recognisable`() {
        val npub = "npub1" + "q".repeat(58)

        val short = shortenNpub(npub)

        assertTrue(short.startsWith("npub1"), "the prefix is how a reader knows what they are looking at")
        assertTrue(short.endsWith(npub.takeLast(6)))
        assertTrue(short.length < npub.length)
    }

    @Test
    fun `something already short is left alone`() {
        assertEquals("npub1short", shortenNpub("npub1short"))
    }
}
