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
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.repo.Credential
import app.wayfarer.core.repo.SignerFactory
import app.wayfarer.core.store.KeyValueStore
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
 * every frame, a signer hook captured once and going stale, a one-time key
 * screen with a navigation bar drawn around it — are invisible to every other
 * test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private val clock = FakeClock()
    private val transport = FakeTransport()

    private suspend fun wayfarer(settings: KeyValueStore = FakeKeyValueStore()): Wayfarer {
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
                    transportFactory = { transport },
                ),
            settings = settings,
            secrets = FakeSecretStore(),
            bootstrapSuggestions = listOf("wss://suggested.example"),
        )
    }

    private val npub = "npub" + "cd".repeat(32)
    private val pubKey = PubKey("cd".repeat(32))

    // ---- startup ----------------------------------------------------------

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
    fun `a first launch opens the introduction rather than making a key`() =
        runTest {
            val core = wayfarer()

            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            assertEquals(OnboardingStep.Start, controller.onboarding.value)
            assertNull(controller.account.value)
            assertTrue(core.relayDirectory.grants.isEmpty())
            assertTrue(transport.fetched.isEmpty(), "nothing may be queried before a relay is approved")
        }

    @Test
    fun `once onboarding is done it is not shown again`() =
        runTest {
            val settings = FakeKeyValueStore()
            val first = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()
            first.continueWithoutAccount()
            first.skipEntryPoint()
            runCurrent()

            val second = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()

            assertNull(second.onboarding.value, "a returning guest is not a new user")
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
                AppController(
                    core,
                    TestScope(testScheduler),
                    externalSignerLogin = { if (installed) ({ null }) else null },
                )
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

    // ---- the introduction -------------------------------------------------

    @Test
    fun `the introduction ends in a choice, having created nothing`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            controller.beginIntroduction()
            repeat(Introduction.pages.size) { controller.introductionNext() }

            assertEquals(OnboardingStep.AccountChoice, controller.onboarding.value)
            assertNull(controller.account.value)
        }

    @Test
    fun `declining an account still lets the user into the app`() =
        runTest {
            val settings = FakeKeyValueStore()
            val controller = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()

            controller.continueWithoutAccount()
            assertEquals(OnboardingStep.EntryPoint, controller.onboarding.value)

            controller.skipEntryPoint()
            runCurrent()

            assertNull(controller.onboarding.value, "the app itself must be reachable without an account")
            assertNull(controller.account.value)
            assertEquals(Screen.Home, controller.screen.value)
        }

    @Test
    fun `a guest session brings the relay client up, once`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            assertEquals(0, transport.startCount, "nothing is started while the introduction is on screen")

            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            controller.refreshFeed()
            runCurrent()

            // Reading is not something only signed-in users do, and starting the
            // client opens no socket on its own — the relay gate still decides that.
            assertEquals(1, transport.startCount)
        }

    // ---- keys -------------------------------------------------------------

    @Test
    fun `a new key is shown on the onboarding surface, which has no tab bar`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            controller.createAccount()
            runCurrent()

            // The whole of the fix for the data-loss bug: while this step is set,
            // WayfarerApp draws onboarding and nothing else, so there is no
            // navigation item that can take the key off screen.
            val step = controller.onboarding.value
            assertTrue(step is OnboardingStep.Backup, "the key must be on the onboarding surface, not inside the app")
            assertTrue(step.nsec.startsWith("nsec"))
            assertTrue(controller.account.value?.hasLocalKey == true)
        }

    @Test
    fun `the key can be read back afterwards, once the device owner is confirmed`() =
        runTest {
            val controller =
                AppController(
                    wayfarer(),
                    TestScope(testScheduler),
                    deviceAuth = { { DeviceAuthOutcome.CONFIRMED } },
                )
            runCurrent()

            controller.createAccount()
            runCurrent()
            val shownAtSetup = (controller.onboarding.value as OnboardingStep.Backup).nsec
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()

            controller.revealSecretKey()
            runCurrent()

            assertEquals(shownAtSetup, controller.revealedSecretKey.value)
        }

    @Test
    fun `a refused device confirmation leaves the key hidden`() =
        runTest {
            val controller =
                AppController(
                    wayfarer(),
                    TestScope(testScheduler),
                    deviceAuth = { { DeviceAuthOutcome.REJECTED } },
                )
            runCurrent()
            controller.createAccount()
            runCurrent()

            controller.revealSecretKey()
            runCurrent()

            assertNull(controller.revealedSecretKey.value)
            assertTrue(controller.message.value is UserMessage.Error)
        }

    @Test
    fun `a watch-only account has no key to show`() =
        runTest {
            val controller =
                AppController(
                    wayfarer(),
                    TestScope(testScheduler),
                    deviceAuth = { { DeviceAuthOutcome.CONFIRMED } },
                )
            runCurrent()
            controller.login(npub)
            runCurrent()

            controller.revealSecretKey()
            runCurrent()

            assertNull(controller.revealedSecretKey.value)
            assertTrue(controller.message.value is UserMessage.Error)
        }

    @Test
    fun `leaving settings takes the key off the screen with it`() =
        runTest {
            val controller =
                AppController(
                    wayfarer(),
                    TestScope(testScheduler),
                    deviceAuth = { { DeviceAuthOutcome.CONFIRMED } },
                )
            runCurrent()
            controller.createAccount()
            runCurrent()
            controller.revealSecretKey()
            runCurrent()

            controller.go(Screen.Home)

            assertNull(controller.revealedSecretKey.value)
        }

    // ---- login ------------------------------------------------------------

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
    fun `logging in asks before querying the relays the app ships with`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            controller.login("nsec" + "ab".repeat(32))
            runCurrent()

            val step = controller.onboarding.value
            assertTrue(step is OnboardingStep.ApproveRelays)
            assertTrue(step.areAppDefaults, "the user must be told these are the app's guess, not theirs")
            assertEquals(core.suggestedRelays, step.relays)
            assertTrue(transport.fetched.isEmpty(), "finding the account must not start before consent")
        }

    // ---- entry points -----------------------------------------------------

    @Test
    fun `a bare npub warns that the app's own relays would be queried`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()

            controller.submitEntryPoint(npub)
            runCurrent()

            val step = controller.onboarding.value
            assertTrue(step is OnboardingStep.ApproveRelays)
            assertTrue(step.areAppDefaults)
            assertEquals(RelayPurpose.FindPerson(pubKey, npub), step.purpose)
            assertTrue(core.relayDirectory.grants.isEmpty(), "the warning must come before the grant")
        }

    @Test
    fun `an nprofile's own relay hints are offered instead of the app's`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()

            controller.submitEntryPoint("nprofile" + "cd".repeat(32) + "@wss://hinted.example")
            runCurrent()

            val step = controller.onboarding.value
            assertTrue(step is OnboardingStep.ApproveRelays)
            assertFalse(step.areAppDefaults)
            assertEquals(listOf(RelayUrl("wss://hinted.example/")), step.relays)
        }

    @Test
    fun `approving the proposed relays is what grants them, and ends onboarding`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.submitEntryPoint(npub)
            runCurrent()

            controller.approveProposedRelays()
            runCurrent()

            assertEquals(core.suggestedRelays.toSet(), core.relayDirectory.grants.keys)
            assertTrue(core.relayDirectory.grants.values.none { it.write }, "reading is all that was asked for")
            assertNull(controller.onboarding.value)
        }

    @Test
    fun `naming your own relay approves that one and none of the app's`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.submitEntryPoint(npub)
            runCurrent()

            controller.useRelayInstead("wss://mine.example")
            runCurrent()

            assertEquals(setOf(RelayUrl("wss://mine.example/")), core.relayDirectory.grants.keys)
            assertNull(controller.onboarding.value)
        }

    @Test
    fun `a relay as the entry point is approved for reading only`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()

            controller.submitEntryPoint("wss://entry.example")
            runCurrent()

            val grant = core.relayDirectory.grants[RelayUrl("wss://entry.example/")]
            assertTrue(grant?.read == true)
            assertFalse(grant?.write == true)
            assertNull(controller.onboarding.value)
        }

    @Test
    fun `an entry point that is neither a relay nor a key is reported`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()

            controller.submitEntryPoint("what is this")
            runCurrent()

            assertTrue(controller.message.value is UserMessage.Error)
            assertEquals(OnboardingStep.EntryPoint, controller.onboarding.value)
        }

    @Test
    fun `a scanned code is treated exactly like a typed one`() =
        runTest {
            val core = wayfarer()
            val controller =
                AppController(
                    core,
                    TestScope(testScheduler),
                    qrScan = { { "wss://scanned.example" } },
                )
            runCurrent()
            controller.continueWithoutAccount()

            assertTrue(controller.qrScanAvailable)
            controller.scanEntryPoint()
            runCurrent()

            assertTrue(core.relayDirectory.grants.containsKey(RelayUrl("wss://scanned.example/")))
        }

    // ---- relay permissions ------------------------------------------------

    @Test
    fun `bootstrap relays are queued for approval, never approved`() =
        runTest {
            val core = wayfarer()

            assertTrue(core.relayDirectory.grants.isEmpty())
            assertEquals(1, core.relayDirectory.pending.size)
        }

    @Test
    fun `allowing a relay reloads the feed instead of waiting to be asked`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            val before = transport.fetched.size

            controller.relays.setPermissions(RelayUrl("wss://suggested.example/"), read = true, write = false)
            runCurrent()

            assertTrue(transport.fetched.size > before, "the feed must reload against the new permission")
        }

    @Test
    fun `the permission list is local, so changing it publishes nothing`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.login("nsec" + "ab".repeat(32))
            runCurrent()

            controller.relays.setPermissions(RelayUrl("wss://suggested.example/"), read = true, write = true)
            runCurrent()

            assertTrue(transport.published.isEmpty(), "approving a relay is not a NIP-65 publish")
        }

    // ---- NIP-65, which is not the permission list ------------------------

    /** Signed in, with the one bootstrap relay allowed for both reading and posting. */
    private fun signedInWithARelay(
        core: Wayfarer,
        scope: TestScope,
    ): AppController {
        val controller = AppController(core, scope)
        controller.login("nsec" + "ab".repeat(32))
        return controller
    }

    @Test
    fun `an unpublished relay list is offered as a draft, not as a fact`() =
        runTest {
            val core = wayfarer()
            val controller = signedInWithARelay(core, TestScope(testScheduler))
            runCurrent()
            controller.relays.setPermissions(RelayUrl("wss://suggested.example/"), read = true, write = true)
            runCurrent()

            controller.openRelayList()
            runCurrent()

            val state = controller.relayList.relayList.value
            assertTrue(state.isSuggestion, "nothing is published, so the rows are a suggestion")
            assertNull(state.publishedAt)
            assertEquals(listOf(RelayUrl("wss://suggested.example/")), state.rows.map { it.url })
            assertTrue(transport.published.isEmpty(), "opening the screen publishes nothing")
        }

    @Test
    fun `publishing the relay list writes a kind 10002 and stops the prompt`() =
        runTest {
            val core = wayfarer()
            val controller = signedInWithARelay(core, TestScope(testScheduler))
            runCurrent()
            controller.relays.setPermissions(RelayUrl("wss://suggested.example/"), read = true, write = true)
            runCurrent()
            assertTrue(controller.shouldOfferRelayListPublish.value, "posting works but nobody can find the posts")
            controller.openRelayList()
            runCurrent()

            controller.relayList.publish()
            runCurrent()

            val (event, relays) = transport.published.single()
            assertEquals(EventKind.RELAY_LIST, event.kind)
            assertEquals(setOf(RelayUrl("wss://suggested.example/")), relays)
            assertFalse(controller.relayList.relayList.value.isSuggestion)
            assertFalse(controller.shouldOfferRelayListPublish.value)
        }

    @Test
    fun `editing the advertised list changes no local permission`() =
        runTest {
            val core = wayfarer()
            val controller = signedInWithARelay(core, TestScope(testScheduler))
            runCurrent()
            controller.relays.setPermissions(RelayUrl("wss://suggested.example/"), read = true, write = true)
            runCurrent()
            controller.openRelayList()
            runCurrent()

            controller.relayList.add("wss://advertised-only.example", read = true, write = true)
            runCurrent()

            val row = controller.relayList.relayList.value.rows.single { it.url == RelayUrl("wss://advertised-only.example/") }
            assertFalse(row.allowedHere, "advertising a relay is not permission to connect to it")
            assertFalse(core.relayDirectory.grants.containsKey(RelayUrl("wss://advertised-only.example/")))
            assertTrue(transport.published.isEmpty(), "an edit is not a publish")

            // And the bridge between them stays explicit, one relay at a time.
            controller.relayList.allowHere(RelayUrl("wss://advertised-only.example/"))
            runCurrent()
            assertTrue(core.relayDirectory.grants.containsKey(RelayUrl("wss://advertised-only.example/")))
        }

    @Test
    fun `a watch-only account can read its relay list but not publish one`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.login(npub)
            runCurrent()
            controller.openRelayList()
            runCurrent()

            assertFalse(controller.relayList.relayList.value.canPublish)

            controller.relayList.publish()
            runCurrent()

            assertTrue(controller.message.value is UserMessage.Error)
            assertTrue(transport.published.isEmpty())
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
