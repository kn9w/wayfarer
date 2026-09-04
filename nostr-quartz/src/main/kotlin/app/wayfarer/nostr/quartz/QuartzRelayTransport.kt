package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.PublishOutcome
import app.wayfarer.core.nostr.ReceivedEvent
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.nostr.ReqFilter
import app.wayfarer.core.relay.RelayAccessPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllWithHooks
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's [RelayTransport], on Quartz's `NostrClient`.
 *
 * Quartz's client is already outbox-shaped — REQs and publishes are addressed
 * per relay rather than broadcast — so this class is mostly translation. The two
 * things it adds are the socket-level approval gate ([GatedWebsocketBuilder])
 * and a second filter of every relay set against the policy on the way in, so a
 * caller cannot reach a relay it has no grant for even by handing one straight
 * to [publish].
 *
 * That second filter is per-direction, which the socket gate cannot be. This is
 * the lowest layer that knows whether a relay is about to be read from or
 * written to, so it is the last place the difference between "Get posts" and
 * "and send mine" can still be enforced.
 */
class QuartzRelayTransport(
    private val policy: RelayAccessPolicy,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    okHttpClient: OkHttpClient = defaultOkHttpClient(),
) : RelayTransport {
    private val client =
        NostrClient(
            websocketBuilder =
                GatedWebsocketBuilder(
                    delegate = BasicOkHttpWebSocket.Builder { okHttpClient },
                    policy = policy,
                ),
            parentScope = scope,
        )

    override val connected: StateFlow<Set<RelayUrl>> =
        client
            .connectedRelaysFlow()
            .map { urls -> urls.mapTo(mutableSetOf()) { it.toCore() } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun start() = client.connect()

    override fun stop() = client.disconnect()

    override fun subscribe(plan: Map<RelayUrl, List<ReqFilter>>): Flow<ReceivedEvent> =
        callbackFlow {
            val quartzPlan = plan.toQuartz()
            if (quartzPlan.isEmpty()) {
                close()
                return@callbackFlow
            }

            val subId = newSubId()
            val listener =
                object : SubscriptionListener {
                    override suspend fun onEvent(
                        event: Event,
                        isLive: Boolean,
                        relay: NormalizedRelayUrl,
                        forFilters: List<Filter>?,
                    ) {
                        QuartzEventMapping.toCore(event)?.let { trySend(ReceivedEvent(it, relay.toCore())) }
                    }
                }

            client.subscribe(subId, quartzPlan, listener)
            awaitClose { client.unsubscribe(subId) }
        }

    override suspend fun fetch(
        plan: Map<RelayUrl, List<ReqFilter>>,
        idleTimeoutMs: Long,
    ): List<ReceivedEvent> {
        val quartzPlan = plan.toQuartz()
        if (quartzPlan.isEmpty()) return emptyList()

        // fetchAllWithHooks rather than the simpler fetchAll: it keeps the
        // delivering relay alongside each event, and provenance is exactly what
        // an outbox client wants to show. It ends when every relay has reached a
        // terminal state (EOSE, CLOSED, unreachable) or the line goes quiet for
        // the idle window, so a fast relay set returns immediately.
        val result =
            client.fetchAllWithHooks(
                filters = quartzPlan,
                idleTimeoutMs = idleTimeoutMs,
            ) { _, _ -> true }

        return result.events.mapNotNull { (relay, event) ->
            QuartzEventMapping.toCore(event)?.let { ReceivedEvent(it, relay.toCore()) }
        }
    }

    override suspend fun publish(
        event: NostrEvent,
        relays: Set<RelayUrl>,
        timeoutSeconds: Long,
    ): Map<RelayUrl, PublishOutcome> {
        // canWrite, not isApproved. This method knows it is sending, so it can ask
        // the question the user actually answered: a relay approved with "Get
        // posts" was approved for reading and for nothing else, and a caller that
        // hands one to publish is a caller with a routing bug, not permission.
        val approved = relays.filterTo(mutableSetOf()) { policy.canWrite(it) }
        if (approved.isEmpty()) return emptyMap()

        return client
            .publishAndCollectResults(
                event = QuartzEventMapping.toQuartz(event),
                relayList = approved.mapTo(mutableSetOf()) { it.toQuartz() },
                timeoutInSeconds = timeoutSeconds,
            ).mapKeys { it.key.toCore() }
            .mapValues { PublishOutcome(accepted = it.value.accepted, message = it.value.message) }
    }

    /**
     * Drops relays not approved for reading, and empty filter lists, before
     * anything reaches the pool.
     *
     * The mirror of [publish]: every plan that comes through here becomes a REQ,
     * so the direction is known and `canRead` is the question. A write-only grant
     * is reachable — the relay sheet toggles the two independently — and means
     * "carry my posts, but I am not reading here".
     */
    private fun Map<RelayUrl, List<ReqFilter>>.toQuartz(): Map<NormalizedRelayUrl, List<Filter>> =
        buildMap {
            for ((url, filters) in this@toQuartz) {
                if (!policy.canRead(url) || filters.isEmpty()) continue
                put(url.toQuartz(), filters.map { it.toQuartz() })
            }
        }

    private fun ReqFilter.toQuartz() =
        Filter(
            ids = ids,
            authors = authors,
            kinds = kinds,
            tags = tags,
            since = since,
            until = until,
            limit = limit,
        )

    companion object {
        /**
         * Kept deliberately plain: no cache, no cookie jar, no interceptors. The
         * only thing a nostr client needs from HTTP is a websocket.
         */
        fun defaultOkHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
