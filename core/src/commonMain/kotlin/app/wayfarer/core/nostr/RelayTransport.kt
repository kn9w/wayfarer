package app.wayfarer.core.nostr

import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.UnsignedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The relay client, as the core sees it.
 *
 * Note the shape of [subscribe] and [publish]: both take a *per-relay* plan, not
 * one filter broadcast everywhere. That is the whole outbox model expressed in a
 * type — the router decides which relay is asked for which authors, and the
 * transport just executes it.
 *
 * Implementations must treat the relay sets they are handed as already
 * authorised, and must additionally refuse any URL the
 * [app.wayfarer.core.relay.RelayAccessPolicy] they were built with does not
 * permit *in the direction being used* — `canRead` for [subscribe] and [fetch],
 * `canWrite` for [publish]. Not `isApproved`: that is the union of the two, and
 * a relay the user allowed to send them posts is not one they agreed to post
 * to.
 */
interface RelayTransport {
    /** Relays with a live socket right now. */
    val connected: StateFlow<Set<RelayUrl>>

    /**
     * Opens a long-lived subscription. Each emission carries the event and the
     * relay it arrived from, so the UI can show provenance.
     *
     * The flow closes when the collector is cancelled; the REQ is closed then too.
     */
    fun subscribe(plan: Map<RelayUrl, List<ReqFilter>>): Flow<ReceivedEvent>

    /**
     * One-shot query: collects until every relay has sent EOSE or the idle
     * timeout elapses, then returns.
     */
    suspend fun fetch(
        plan: Map<RelayUrl, List<ReqFilter>>,
        idleTimeoutMs: Long = 10_000,
    ): List<ReceivedEvent>

    /** Publishes to each relay and reports what each one said. */
    suspend fun publish(
        event: NostrEvent,
        relays: Set<RelayUrl>,
        timeoutSeconds: Long = 15,
    ): Map<RelayUrl, PublishOutcome>

    fun start()

    fun stop()
}

/** A NIP-01 REQ filter. */
data class ReqFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
)

data class ReceivedEvent(
    val event: NostrEvent,
    val relay: RelayUrl,
)

/** One relay's verdict on a published event. */
data class PublishOutcome(
    val accepted: Boolean,
    /** The relay's OK message, or the transport error that stood in for one. */
    val message: String,
)

/** Signs events with the account's key. */
interface EventSigner {
    val pubKeyHex: String

    /** False for a watch-only (npub) login: everything is readable, nothing publishable. */
    val canSign: Boolean

    /** Throws if [canSign] is false. */
    suspend fun sign(unsigned: UnsignedEvent): NostrEvent
}
