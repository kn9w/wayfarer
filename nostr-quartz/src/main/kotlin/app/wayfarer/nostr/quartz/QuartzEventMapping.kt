package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl

/**
 * The boundary between Quartz's event model and the app's.
 *
 * Everything crossing into the core goes through [toCore]; nothing else in the
 * app may hold a Quartz [Event]. That is the single rule that keeps the backend
 * replaceable.
 */
internal object QuartzEventMapping {
    /** Null when a relay sends an event whose id or pubkey is not valid hex. */
    fun toCore(event: Event): NostrEvent? {
        val id = EventId.parseOrNull(event.id) ?: return null
        val pubKey = PubKey.parseOrNull(event.pubKey) ?: return null
        return NostrEvent(
            id = id,
            pubKey = pubKey,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags.map { it.toList() },
            content = event.content,
            sig = event.sig,
        )
    }

    fun toQuartz(event: NostrEvent): Event =
        Event(
            id = event.id.hex,
            pubKey = event.pubKey.hex,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags.map { it.toTypedArray() }.toTypedArray(),
            content = event.content,
            sig = event.sig,
        )
}

internal fun NormalizedRelayUrl.toCore() = RelayUrl(url)

internal fun RelayUrl.toQuartz() = NormalizedRelayUrl(url)
