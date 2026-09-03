package app.wayfarer.android.signer

import app.wayfarer.core.model.EventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip55ProtocolTest {
    @Test
    fun `get_public_key is broadcast so any installed signer can answer`() {
        val request = Nip55Protocol.getPublicKey()

        assertNull(request.packageName)
        assertEquals("nostrsigner:", request.uri)
        assertEquals("get_public_key", request.extras[Nip55Protocol.EXTRA_TYPE])
    }

    @Test
    fun `sign_event is addressed to the signer that answered`() {
        val request =
            Nip55Protocol.signEvent(
                eventJson = """{"kind":1,"content":"hi"}""",
                currentUserHex = "ab".repeat(32),
                signerPackage = "com.example.signer",
                id = "1-1700000000",
            )

        assertEquals("com.example.signer", request.packageName)
        assertEquals("""nostrsigner:{"kind":1,"content":"hi"}""", request.uri)
        assertEquals("sign_event", request.extras[Nip55Protocol.EXTRA_TYPE])
        assertEquals("ab".repeat(32), request.extras[Nip55Protocol.EXTRA_CURRENT_USER])
        assertEquals("1-1700000000", request.extras[Nip55Protocol.EXTRA_ID])
    }

    @Test
    fun `permissions are encoded as the spec's array of objects`() {
        val encoded =
            Nip55Protocol.encodePermissions(
                listOf(
                    Nip55Protocol.Permission("sign_event", kind = 22242),
                    Nip55Protocol.Permission("nip44_decrypt"),
                ),
            )

        assertEquals("""[{"type":"sign_event","kind":22242},{"type":"nip44_decrypt"}]""", encoded)
    }

    @Test
    fun `the default permissions cover every kind this app signs`() {
        // Kept in step with the six signer.sign call sites by hand, because the
        // list lives in the app module and the kinds live in core. A kind that
        // falls off here still signs — it just prompts every time — so the
        // failure it guards against is silent, and only external-signer users
        // ever feel it.
        val kinds =
            Nip55Protocol.DEFAULT_PERMISSIONS
                .filter { it.type == "sign_event" }
                .mapNotNull { it.kind }
                .toSet()

        assertEquals(
            setOf(
                EventKind.METADATA,
                EventKind.TEXT_NOTE,
                EventKind.CONTACT_LIST,
                EventKind.COMMENT,
                EventKind.RELAY_LIST,
                EventKind.LONG_FORM,
            ),
            kinds,
        )
    }

    @Test
    fun `a rejection is not reported as a signer failure`() {
        val reply = Nip55Protocol.parseReply(resultOk = true, extras = mapOf("rejected" to "true"))

        assertEquals(Nip55Protocol.Reply.Rejected, reply)
    }

    @Test
    fun `a non-OK result code is a signer failure, not a rejection`() {
        // The spec is explicit that these are different outcomes: conflating them
        // either blames the user for a crash or retries forever against a "no".
        val reply = Nip55Protocol.parseReply(resultOk = false, extras = null)

        assertTrue(reply is Nip55Protocol.Reply.Failed)
    }

    @Test
    fun `a signed event and the answering package are read back`() {
        val reply =
            Nip55Protocol.parseReply(
                resultOk = true,
                extras =
                    mapOf(
                        "result" to "npub1example",
                        "event" to """{"id":"aa"}""",
                        "package" to "com.example.signer",
                    ),
            )

        val ok = reply as Nip55Protocol.Reply.Ok
        assertEquals("npub1example", ok.result)
        assertEquals("""{"id":"aa"}""", ok.event)
        assertEquals("com.example.signer", ok.packageName)
    }

    @Test
    fun `an empty answer is a failure rather than a silent success`() {
        assertTrue(Nip55Protocol.parseReply(resultOk = true, extras = emptyMap()) is Nip55Protocol.Reply.Failed)
        assertTrue(Nip55Protocol.parseReply(resultOk = true, extras = null) is Nip55Protocol.Reply.Failed)
    }
}
