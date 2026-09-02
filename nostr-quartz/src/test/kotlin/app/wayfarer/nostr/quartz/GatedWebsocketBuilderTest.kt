package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.relay.RelayAccessPolicy
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's central promise is "no socket is opened to a relay the user has not
 * approved". These tests assert it at the only layer that can actually open one.
 */
class GatedWebsocketBuilderTest {
    private val approved = NormalizedRelayUrl("wss://approved.example/")
    private val unapproved = NormalizedRelayUrl("wss://unapproved.example/")

    private val policy = RelayAccessPolicy { it == RelayUrl(approved.url) }

    private class RecordingBuilder : WebsocketBuilder {
        val built = mutableListOf<NormalizedRelayUrl>()

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            built += url
            return object : WebSocket {
                override fun needsReconnect() = false

                override fun connect() = Unit

                override fun disconnect() = Unit

                override fun send(msg: String) = true
            }
        }
    }

    private object SilentListener : WebSocketListener {
        var failures = 0

        override fun onOpen(
            pingMillis: Int,
            compression: Boolean,
        ) = Unit

        override suspend fun onMessage(text: String) = Unit

        override fun onClosed(
            code: Int,
            reason: String,
        ) = Unit

        override fun onFailure(
            t: Throwable,
            code: Int?,
            response: String?,
        ) {
            failures++
        }
    }

    @Test
    fun `canConnect is false for a relay with no grant`() {
        val gate = GatedWebsocketBuilder(RecordingBuilder(), policy)

        assertTrue(gate.canConnect(approved))
        assertFalse(gate.canConnect(unapproved))
    }

    @Test
    fun `the real socket is never built for an unapproved relay`() {
        val delegate = RecordingBuilder()
        val gate = GatedWebsocketBuilder(delegate, policy)

        gate.build(unapproved, SilentListener)

        assertEquals(emptyList(), delegate.built)
    }

    @Test
    fun `an approved relay reaches the real builder`() {
        val delegate = RecordingBuilder()
        val gate = GatedWebsocketBuilder(delegate, policy)

        gate.build(approved, SilentListener)

        assertEquals(listOf(approved), delegate.built)
    }

    @Test
    fun `the blocked socket refuses to connect or send, and reports the refusal`() {
        val gate = GatedWebsocketBuilder(RecordingBuilder(), policy)
        val before = SilentListener.failures

        val socket = gate.build(unapproved, SilentListener)
        socket.connect()

        assertEquals(before + 1, SilentListener.failures)
        assertFalse(socket.send("[\"REQ\",\"x\",{}]"))
        // Still needs a reconnect, so approving the relay later lets the pool dial it.
        assertTrue(socket.needsReconnect())
    }

    @Test
    fun `the block callback names the relay that was refused`() {
        val blocked = mutableListOf<NormalizedRelayUrl>()
        val gate = GatedWebsocketBuilder(RecordingBuilder(), policy) { blocked += it }

        gate.build(unapproved, SilentListener)
        gate.build(approved, SilentListener)

        assertEquals(listOf(unapproved), blocked)
    }

    @Test
    fun `a delegate that is not ready still vetoes an approved relay`() {
        val notReady =
            object : WebsocketBuilder {
                override fun build(
                    url: NormalizedRelayUrl,
                    out: WebSocketListener,
                ) = throw AssertionError("must not be built")

                override fun canConnect(url: NormalizedRelayUrl) = false
            }

        assertFalse(GatedWebsocketBuilder(notReady, policy).canConnect(approved))
    }
}
