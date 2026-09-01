package app.wayfarer.core

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayUrlNormalizer
import app.wayfarer.core.util.Clock

fun pubKey(seed: Int): PubKey = PubKey(seed.toString(16).padStart(2, '0').repeat(32).take(64))

fun relay(host: String): RelayUrl = RelayUrl("wss://$host/")

/** Just enough normalization to make the tests meaningful; the real one is Quartz's. */
val testNormalizer =
    RelayUrlNormalizer { raw ->
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty()) {
            null
        } else {
            val withScheme = if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) trimmed else "wss://$trimmed"
            RelayUrl(if (withScheme.endsWith("/")) withScheme else "$withScheme/")
        }
    }

class FakeClock(
    var now: Long = 1_700_000_000,
) : Clock {
    override fun nowSeconds(): Long = now
}
