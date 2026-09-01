package app.wayfarer.android.viewmodel

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Below this, a post is described by its age; above it, by its date. */
private const val RELATIVE_WINDOW_SECONDS = 7L * 24 * 60 * 60

private const val ABSOLUTE_PATTERN = "d MMM yyyy"

/**
 * How old a post is, in the terms a reader actually wants.
 *
 * Recent things are ages — "3h" answers "did I already see this?" without any
 * arithmetic. Old things are dates, because "37w" answers nothing: past about a
 * week nobody counts, and the calendar is the more useful fact.
 *
 * `java.time` directly rather than a datetime dependency: minSdk is 26, so it is
 * present without desugaring, and the whole need is one formatter.
 */
fun formatTimestamp(
    epochSeconds: Long,
    nowSeconds: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    // A relay is free to hand back a created_at in the future — an author's
    // clock is theirs, not ours. A negative age falls into the first branch
    // below and reads as "now", which is the right answer and the reason no
    // clamp is needed here; one would be dead code.
    val age = nowSeconds - epochSeconds

    return when {
        age < 60 -> "now"
        age < 60 * 60 -> "${age / 60}m"
        age < 24 * 60 * 60 -> "${age / (60 * 60)}h"
        age < RELATIVE_WINDOW_SECONDS -> "${age / (24 * 60 * 60)}d"
        else ->
            DateTimeFormatter
                .ofPattern(ABSOLUTE_PATTERN, locale)
                .format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
    }
}

/**
 * A pubkey as a person can read it: shortened, and always bech32.
 *
 * Never hex. An npub is the only form of a key anyone recognises or can paste
 * anywhere else, and showing the hex — which is what `PubKey.abbreviated()`
 * does — meant the same stranger appeared under two different identities on two
 * screens of this app.
 */
fun shortenNpub(npub: String): String = if (npub.length <= 20) npub else npub.take(12) + "…" + npub.takeLast(6)
