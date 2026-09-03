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

/**
 * One or two letters to draw in place of somebody's photograph.
 *
 * The input is whatever `displayName` returned, which for a stranger is a
 * shortened npub — and there the leading `npub1` is the one part every key on
 * the network shares, so taking the first two characters would draw the same
 * mark for everybody. The distinctive part starts after the prefix, so that is
 * what is used.
 *
 * Falls back to a single dot rather than an empty string: a mark with nothing in
 * it reads as a rendering failure, and a name can be any string at all.
 */
fun initialsOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "·"

    val distinctive = if (trimmed.startsWith("npub1")) trimmed.drop(5) else trimmed
    val words = distinctive.split(' ', '\t', '\n').filter { it.isNotBlank() }

    val letters =
        when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            else -> words.firstOrNull()?.take(2) ?: "·"
        }
    return letters.uppercase()
}
