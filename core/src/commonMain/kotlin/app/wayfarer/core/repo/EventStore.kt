package app.wayfarer.core.repo

import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The events themselves, as they arrived.
 *
 * [Note], [app.wayfarer.core.model.Article] and [app.wayfarer.core.model.Comment]
 * are projections: they keep what a reader sees and drop the rest, which is the
 * right shape for a feed and the wrong shape for showing somebody the event they
 * are looking at, or handing it back to a relay unchanged. Neither is possible
 * from a projection — the signature, the id and the tags are gone.
 *
 * One store shared by every repository rather than a raw field on each model, so
 * that adding it costs no change to the models or to anything constructing them,
 * and so there is a single place to bound it if a long session ever needs that.
 *
 * Re-offering an event is a no-op. An id is the hash of its own event and absorb
 * verifies before storing, so a second copy is always byte-identical and there is
 * no conflict to resolve; where a note was seen accumulates on
 * [app.wayfarer.core.model.Note.seenOn], not here.
 *
 * The early return in [put] is an allocation short-circuit and nothing more —
 * a StateFlow already discards an assignment equal to its current value, so the
 * map instance and every collector are unaffected either way. It is there because
 * relays echo each other constantly, and building a map per echo to throw it away
 * is waste on the hottest path in the app. No test pins it, because there is no
 * observable behaviour to pin.
 */
class EventStore {
    private val events = MutableStateFlow<Map<EventId, NostrEvent>>(emptyMap())

    val all: StateFlow<Map<EventId, NostrEvent>> = events.asStateFlow()

    operator fun get(id: EventId): NostrEvent? = events.value[id]

    fun put(event: NostrEvent) {
        if (event.id in events.value) return
        events.value = events.value + (event.id to event)
    }

    val size: Int get() = events.value.size
}
