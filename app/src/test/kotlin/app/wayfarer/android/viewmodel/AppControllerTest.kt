package app.wayfarer.android.viewmodel

import app.wayfarer.core.FakeBech32Codec
import app.wayfarer.core.FakeClock
import app.wayfarer.core.FakeCodec
import app.wayfarer.core.FakeKeyTool
import app.wayfarer.core.FakeKeyValueStore
import app.wayfarer.core.FakeSecretStore
import app.wayfarer.core.FakeTransport
import app.wayfarer.core.NostrBackend
import app.wayfarer.core.UnusedRelayInfoFetcher
import app.wayfarer.core.Wayfarer
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.repo.Credential
import app.wayfarer.core.repo.SignerFactory
import app.wayfarer.core.testNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wiring-level checks on the layer between the UI and the core.
 *
 * These exist because the Compose tree cannot be exercised in this project's
 * offline harness, and the bugs that layer produces — a controller rebuilt on
 * every frame, a signer hook captured once and going stale — are invisible to
 * every other test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private val clock = FakeClock()

    private suspend fun wayfarer(): Wayfarer {
        var seed = 0
        return Wayfarer.create(
            backend =
                NostrBackend(
                    codec = FakeCodec(),
                    bech32 = FakeBech32Codec,
                    keyTool = FakeKeyTool { ++seed },
                    normalizer = testNormalizer,
                    signerFactory =
                        SignerFactory { pubKey, credential ->
                            app.wayfarer.core.FakeSigner(pubKey, canSign = credential !is Credential.WatchOnly)
                        },
                    clock = clock,
                    relayInfoFetcher = UnusedRelayInfoFetcher,
                    transportFactory = { FakeTransport() },
                ),
            settings = FakeKeyValueStore(),
            secrets = FakeSecretStore(),
            bootstrapSuggestions = listOf("wss://suggested.example"),
        )
    }

    @Test
    fun `starting with no stored account signs nobody in and does not fail`() =
        runTest {
            val core = wayfarer()

            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            assertNull(controller.account.value)
            assertNull(controller.message.value)
        }

    @Test
    fun `the external signer option follows the provider, not a captured instance`() =
        runTest {
            val core = wayfarer()
            var installed = false

            // The activity hands over a fresh lambda on every recomposition; the
            // controller must read through the provider each time rather than
            // holding whichever instance it saw first.
            val controller =
                AppController(core, TestScope(testScheduler)) {
                    if (installed) ({ null }) else null
                }
            runCurrent()

            assertFalse(controller.externalSignerAvailable)
            installed = true
            assertTrue(controller.externalSignerAvailable)
        }

    @Test
    fun `with no signer installed the option stays hidden by default`() =
        runTest {
            val core = wayfarer()

            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            assertFalse(controller.externalSignerAvailable)
        }

    @Test
    fun `a bad key at login is reported rather than thrown`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            controller.login("definitely not a key")
            runCurrent()

            assertTrue(controller.message.value is UserMessage.Error)
            assertNull(controller.account.value)
        }

    @Test
    fun `logging in with an nsec produces a signing account`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            controller.login("nsec" + "ab".repeat(32))
            runCurrent()

            val account = controller.account.value
            assertEquals(PubKey("ab".repeat(32)), account?.pubKey)
            assertTrue(account?.canSign == true)
        }

    @Test
    fun `bootstrap relays are queued for approval, never approved`() =
        runTest {
            val core = wayfarer()

            assertTrue(core.relayDirectory.grants.isEmpty())
            assertEquals(1, core.relayDirectory.pending.size)
        }

    @Test
    fun `relay info is not fetched until the user asks`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            // UnusedRelayInfoFetcher throws if reached; a pending relay must only
            // raise the confirmation prompt.
            controller.requestRelayInfo(core.relayDirectory.pending.keys.first())
            runCurrent()

            assertEquals(core.relayDirectory.pending.keys.first(), controller.relayInfoPrompt.value)
        }
}
