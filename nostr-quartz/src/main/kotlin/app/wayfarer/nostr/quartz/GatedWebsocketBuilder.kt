package app.wayfarer.nostr.quartz

import app.wayfarer.core.relay.RelayAccessPolicy
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder

/**
 * The relay approval gate, enforced at the transport.
 *
 * Routing already refuses to name an unapproved relay in any plan, so in normal
 * operation this decorator never rejects anything. It exists because "no socket
 * is ever opened to a relay the user did not approve" is the app's central
 * promise, and a promise enforced only by the code that computes relay sets is
 * one routing bug away from being false. Here it is enforced by the one object
 * that can actually open a connection.
 *
 * `canConnect` returning false makes Quartz's `BasicRelayClient` skip the dial
 * entirely — no socket, no backoff growth — and [build] hands back a socket that
 * refuses to connect, so even a caller that ignores `canConnect` gets nothing.
 *
 * [RelayAccessPolicy.isApproved] rather than `canRead`/`canWrite`, and this is
 * the one place where the union is the correct question. A websocket carries
 * both directions: the same connection sends `REQ` and `EVENT`, and a relay
 * approved for reading alone still needs a real socket to be read from. There is
 * no direction to check at dial time, because the direction is a property of the
 * messages and not of the connection.
 *
 * So this gate answers "may this app talk to this relay at all", and per-direction
 * enforcement lives one layer up in [QuartzRelayTransport], where `publish` and
 * the `REQ` builder each know which they are and ask accordingly. Adding a
 * direction here would mean guessing one.
 */
class GatedWebsocketBuilder(
    private val delegate: WebsocketBuilder,
    private val policy: RelayAccessPolicy,
    private val onBlocked: (NormalizedRelayUrl) -> Unit = {},
) : WebsocketBuilder {
    override fun canConnect(url: NormalizedRelayUrl): Boolean = policy.isApproved(url.toCore()) && delegate.canConnect(url)

    override fun build(
        url: NormalizedRelayUrl,
        out: WebSocketListener,
    ): WebSocket {
        if (!policy.isApproved(url.toCore())) {
            onBlocked(url)
            return BlockedWebSocket(url, out)
        }
        return delegate.build(url, out)
    }
}

/**
 * Stands in for a connection to an unapproved relay.
 *
 * It reports the refusal through the normal failure path rather than throwing,
 * so the pool treats it as an unreachable relay and moves on instead of taking
 * down whatever coroutine asked for it. [needsReconnect] stays true so that if
 * the user later approves the relay, the pool's next reconnect pass dials it for
 * real.
 */
private class BlockedWebSocket(
    private val url: NormalizedRelayUrl,
    private val out: WebSocketListener,
) : WebSocket {
    override fun needsReconnect(): Boolean = true

    override fun connect() {
        out.onFailure(SecurityException("Relay ${url.url} is not approved by the user"), null, null)
    }

    override fun disconnect() = Unit

    override fun send(msg: String): Boolean = false
}
