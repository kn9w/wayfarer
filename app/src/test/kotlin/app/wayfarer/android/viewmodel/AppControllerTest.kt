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
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.MediaSource
import app.wayfarer.core.model.PaymentTarget
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.noteEvent
import app.wayfarer.core.profileEvent
import app.wayfarer.core.paymentEvent
import app.wayfarer.core.pubKey
import app.wayfarer.core.relay
import app.wayfarer.core.repo.Credential
import app.wayfarer.core.repo.HeaderStyle
import app.wayfarer.core.repo.SignerFactory
import app.wayfarer.core.store.KeyValueStore
import app.wayfarer.core.testNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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

    private val pubKey = PubKey("cd".repeat(32))

    // From the codec rather than concatenated: an npub is not its hex with a
    // prefix, and a literal that pretended otherwise would hide exactly the bug
    // these tests are here to catch.
    private val npub = FakeBech32Codec.encodeNpub(pubKey)

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

    // ---- streaming and the foreground ------------------------------------

    /**
     * Reaches the state a guest lands in: past onboarding, one relay approved,
     * and in the foreground.
     */
    private suspend fun browsingGuest(scope: TestScope): AppController {
        val controller = AppController(wayfarer(), scope)
        scope.testScheduler.runCurrent()
        controller.continueWithoutAccount()
        controller.skipEntryPoint()
        scope.testScheduler.runCurrent()
        controller.onEnterForeground()
        controller.relays.add("wss://open.example", read = true, write = false)
        scope.testScheduler.runCurrent()
        return controller
    }

    @Test
    fun `nothing is subscribed to while onboarding is still on screen`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            controller.onEnterForeground()
            runCurrent()

            // Onboarding is the conversation about what may be contacted. Opening
            // a REQ during it would be answering the question on the user's behalf.
            assertTrue(transport.subscribed.isEmpty())
            assertEquals(0, transport.startCount)
        }

    @Test
    fun `approving a relay opens a live subscription to it`() =
        runTest {
            val controller = browsingGuest(TestScope(testScheduler))

            // The whole point of the change: the socket is held by a REQ rather
            // than opened for one fetch and dropped at EOSE.
            assertTrue(transport.subscribed.isNotEmpty(), "an approved relay must be subscribed to, not just fetched")
            assertEquals(setOf(RelayUrl("wss://open.example/")), transport.subscribed.last().keys)
            assertEquals(1, transport.openSubscriptions)
            assertNull(controller.message.value)
        }

    @Test
    fun `a note arriving on the subscription reaches the feed with no refresh`() =
        runTest {
            val controller = browsingGuest(TestScope(testScheduler))
            assertTrue(controller.feed.value.notes.isEmpty())

            transport.emit(
                app.wayfarer.core.nostr.ReceivedEvent(
                    app.wayfarer.core.noteEvent(pubKey, "streamed in", createdAt = 500),
                    RelayUrl("wss://open.example/"),
                ),
            )
            runCurrent()

            assertEquals(listOf("streamed in"), controller.feed.value.notes.map { it.content })
        }

    @Test
    fun `the same note arriving twice is not shown twice`() =
        runTest {
            val controller = browsingGuest(TestScope(testScheduler))
            val event = app.wayfarer.core.noteEvent(pubKey, "echoed", createdAt = 500)

            transport.emit(app.wayfarer.core.nostr.ReceivedEvent(event, RelayUrl("wss://open.example/")))
            runCurrent()
            transport.emit(app.wayfarer.core.nostr.ReceivedEvent(event, RelayUrl("wss://other.example/")))
            runCurrent()

            assertEquals(1, controller.feed.value.notes.size, "a note echoed by a second relay is still one note")
        }

    @Test
    fun `leaving the foreground closes the subscription and drops the sockets`() =
        runTest {
            val controller = browsingGuest(TestScope(testScheduler))
            assertEquals(1, transport.openSubscriptions)
            transport.connectedRelays.value = setOf(RelayUrl("wss://open.example/"))

            controller.onLeaveForeground()
            runCurrent()

            assertEquals(0, transport.openSubscriptions, "backgrounding must close the REQ")
            assertEquals(1, transport.stopCount)
            assertTrue(transport.connected.value.isEmpty())
        }

    @Test
    fun `coming back to the foreground brings the transport and the stream back`() =
        runTest {
            val controller = browsingGuest(TestScope(testScheduler))
            val subscriptionsBefore = transport.subscribed.size
            val fetchesBefore = transport.fetched.size

            controller.onLeaveForeground()
            runCurrent()
            assertEquals(1, transport.stopCount)

            controller.onEnterForeground()
            runCurrent()

            // The latch that guards start() has to be released by stop(), or the
            // client could never be brought back up for a second session.
            assertEquals(2, transport.startCount, "the transport must be startable again after a stop")
            assertTrue(transport.subscribed.size > subscriptionsBefore, "the REQ must be reopened")
            assertEquals(1, transport.openSubscriptions)
            // The subscription only carries what happens from now on, so resuming
            // has to fetch as well or everything posted while away is missing.
            assertTrue(transport.fetched.size > fetchesBefore, "resuming must also reload the backlog")
        }

    // ---- relay provenance and focus ---------------------------------------

    @Test
    fun `a relay hinted by a post you read becomes pending, attributed`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            controller.onEnterForeground()
            controller.relays.add("wss://open.example", read = true, write = false)
            runCurrent()

            transport.emit(
                app.wayfarer.core.nostr.ReceivedEvent(
                    app.wayfarer.core.noteEvent(
                        pubKey,
                        "hi",
                        createdAt = 500,
                        idSeed = 77,
                        tags = listOf(listOf("e", "11".repeat(32), "wss://hinted.example", "root")),
                    ),
                    RelayUrl("wss://open.example/"),
                ),
            )
            runCurrent()
            // Recorded on the batching tick, not inside the collector: a write
            // there would stall every event queued behind it.
            advanceTimeBy(3_000)
            runCurrent()

            val hinted = RelayUrl("wss://hinted.example/")
            val reasons = controller.relays.state.value.pending.firstOrNull { it.url == hinted }?.reasons
            assertTrue(reasons != null && reasons.isNotEmpty(), "the hinted relay must be queued for a decision")
            assertTrue(
                reasons.any { it.detail?.contains("you read") == true },
                "and must say which post caused it, was: ${reasons.map { it.detail }}",
            )
        }

    @Test
    fun `absorbing an event with hints costs no relay round trip`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            controller.onEnterForeground()
            controller.relays.add("wss://open.example", read = true, write = false)
            runCurrent()
            val before = transport.fetched.size

            transport.emit(
                app.wayfarer.core.nostr.ReceivedEvent(
                    app.wayfarer.core.noteEvent(
                        pubKey,
                        "hi",
                        createdAt = 500,
                        idSeed = 78,
                        tags = listOf(listOf("e", "11".repeat(32), "wss://hinted.example", "root")),
                    ),
                    RelayUrl("wss://open.example/"),
                ),
            )
            runCurrent()

            assertEquals(before, transport.fetched.size, "harvesting must not reach the network from the collector")
        }

    @Test
    fun `opening a relay nobody has mentioned still finds it something to show`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            val stranger = RelayUrl("wss://never-heard-of.example/")

            controller.openRelayDetail(stranger, because = "npub1abc says they can be found here")
            runCurrent()

            // Without the pending record the relay screen would find no row for
            // it and close the sheet again, so the tap would look broken.
            val pending = controller.relays.state.value.pending.firstOrNull { it.url == stranger }
            assertTrue(pending != null, "an unknown relay must be recorded before it can be shown")
            assertTrue(pending.reasons.any { it.detail?.contains("can be found here") == true })
            assertEquals(stranger, controller.relayFocus.value)
            assertEquals(Screen.Relays, controller.screen.value)

            controller.clearRelayFocus()
            assertNull(controller.relayFocus.value, "the focus is consumed, so back-then-forward does not reopen it")
        }

    @Test
    fun `an already known relay is focused without being re-recorded`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            controller.relays.add("wss://known.example", read = true, write = false)
            runCurrent()
            val known = RelayUrl("wss://known.example/")

            controller.openRelayDetail(known, because = "somebody says so")
            runCurrent()

            assertEquals(known, controller.relayFocus.value)
            assertTrue(controller.relays.state.value.pending.none { it.url == known }, "an allowed relay stays allowed")
        }

    // ---- header appearance -------------------------------------------------

    @Test
    fun `the header is standard until the user says otherwise`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            assertEquals(HeaderStyle.Standard, controller.headerStyle.value)

            controller.setHeaderStyle(HeaderStyle.Compact)
            runCurrent()
            assertEquals(HeaderStyle.Compact, controller.headerStyle.value)
        }

    // ---- how people are named ---------------------------------------------

    @Test
    fun `an author with no profile is named by npub, never by hex`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            val name = controller.displayName(pubKey)

            // The old fallback was PubKey.abbreviated(), which is hex — so the
            // same stranger appeared as an npub on one screen and as truncated
            // hex on the next.
            assertTrue(name.startsWith("npub"), "a person is an npub, was: $name")
            assertFalse(name.contains(pubKey.hex.take(8)), "no part of the raw hex key may reach the screen")
        }

    // ---- back navigation --------------------------------------------------

    @Test
    fun `back returns to the screen you came from`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            controller.go(Screen.Relays)
            controller.go(Screen.Compose)
            runCurrent()

            assertTrue(controller.back())
            assertEquals(Screen.Relays, controller.screen.value)
            assertTrue(controller.back())
            assertEquals(Screen.Home, controller.screen.value)
        }

    @Test
    fun `back from home is the app's own exit`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            // Nothing behind Home, so the press belongs to the system. Returning
            // true here would trap the user in the app.
            assertFalse(controller.back())
            assertFalse(controller.canGoBack.value)
        }

    @Test
    fun `a screen reached without history still goes somewhere sensible`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.goToRoot(Screen.Relays)

            assertTrue(controller.canGoBack.value)
            assertTrue(controller.back())
            assertEquals(Screen.Home, controller.screen.value)
        }

    @Test
    fun `switching tabs does not build history to unwind`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            controller.go(Screen.Compose)
            controller.go(Screen.Settings)
            runCurrent()
            controller.goToRoot(Screen.Relays)
            runCurrent()

            // One press leaves the tab, rather than walking back through every
            // screen visited before it — Settings and Compose are behind this
            // point and must not be revisited.
            assertTrue(controller.back())
            assertEquals(Screen.Home, controller.screen.value)
            assertFalse(controller.back())
        }

    @Test
    fun `cancelling a compose opened from a profile returns to that profile`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            controller.openProfile(pubKey)
            runCurrent()
            controller.go(Screen.Compose)
            runCurrent()

            // The old hand-rolled Cancel sent every compose to Home, which was
            // wrong exactly here.
            assertTrue(controller.back())
            assertEquals(Screen.Profile(pubKey), controller.screen.value)
        }

    // ---- whose relay permissions these are --------------------------------

    @Test
    fun `a guest's relay permissions do not outlive the session`() =
        runTest {
            val settings = FakeKeyValueStore()
            val first = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()
            first.continueWithoutAccount()
            first.skipEntryPoint()
            runCurrent()
            first.relays.add("wss://open.example", read = true, write = false)
            runCurrent()
            assertEquals(1, first.relays.state.value.approved.size, "a guest can allow relays and read")

            // A relaunch: the same stored settings, everything else new.
            val second = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()

            // Nobody granted anything in *this* session, so nothing is allowed —
            // and the app says so by asking the one question it cannot proceed
            // without, rather than opening onto an empty feed.
            assertTrue(second.relays.state.value.approved.isEmpty())
            assertEquals(OnboardingStep.EntryPoint, second.onboarding.value)
        }

    @Test
    fun `an account's relay permissions come back with the account`() =
        runTest {
            val settings = FakeKeyValueStore()
            val first = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()
            first.createAccount()
            runCurrent()
            first.finishBackup()
            first.skipEntryPoint()
            runCurrent()
            first.relays.add("wss://open.example", read = true, write = false)
            runCurrent()

            val second = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()

            // Forgotten on the way out, restored on the way in — the same rule
            // the follow list kept on this phone has always followed.
            assertEquals(1, second.relays.state.value.approved.size)
            assertNull(second.onboarding.value, "a session that can already reach relays is not asked again")
        }

    @Test
    fun `switching accounts swaps the permissions with them`() =
        runTest {
            val settings = FakeKeyValueStore()
            val controller = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()

            // One account with a relay allowed…
            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()
            val first = assertNotNull(controller.account.value).pubKey
            controller.relays.add("wss://first.example", read = true, write = false)
            runCurrent()

            // …and a second, which starts out allowed nothing.
            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()
            assertTrue(
                controller.relays.state.value.approved.isEmpty(),
                "a new account does not inherit what another one allowed",
            )

            controller.switchTo(first)
            runCurrent()

            assertEquals(first, controller.account.value?.pubKey)
            assertEquals(1, controller.relays.state.value.approved.size, "and switching back brings that list back")
        }

    @Test
    fun `logging out erases the permissions rather than putting them away`() =
        runTest {
            val settings = FakeKeyValueStore()
            val controller = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()
            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()
            val leaving = assertNotNull(controller.account.value).pubKey
            controller.relays.add("wss://open.example", read = true, write = false)
            controller.media.add("image.example")
            runCurrent()

            controller.followLocally(pubKey)
            runCurrent()
            assertTrue(controller.localFollows.value.isNotEmpty(), "with something private to leave behind")

            controller.logout()
            runCurrent()

            // Not merely out of scope: gone. Permissions are standing consent to
            // open a socket, and the follow list kept on this phone is the most
            // private thing the app holds — an account that has left must not be
            // able to resume either by signing back in, and nobody using the
            // phone afterwards should be able to find them.
            assertTrue(
                settings.values.keys.none { it.contains(leaving.hex) },
                "no record of what that account allowed or read is left behind",
            )
            assertTrue(
                settings.values.values.none { it.contains(pubKey.hex) },
                "including who it was quietly following",
            )
        }

    @Test
    fun `logging out throws away the pictures that were fetched`() =
        runTest {
            var cleared = 0
            val controller =
                AppController(
                    wayfarer(),
                    TestScope(testScheduler),
                    clearImageCache = { { cleared++ } },
                )
            runCurrent()
            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()

            controller.logout()
            runCurrent()

            // The faces and photographs a session drew are a legible trace of
            // who that account was reading, and a cache hit is answered before
            // the permission gate is consulted.
            assertEquals(1, cleared)
        }

    @Test
    fun `logging out of a second account hands the app back to the first`() =
        runTest {
            val settings = FakeKeyValueStore()
            val controller = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()
            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()
            val first = assertNotNull(controller.account.value).pubKey

            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()

            controller.logout()
            runCurrent()

            // Leaving one identity is not leaving the app, so there is no reason
            // to put anybody through the front door.
            assertEquals(first, controller.account.value?.pubKey)
            assertNull(controller.onboarding.value)
        }

    @Test
    fun `logging out leaves a session that may talk to nothing`() =
        runTest {
            val settings = FakeKeyValueStore()
            val controller = AppController(wayfarer(settings), TestScope(testScheduler))
            runCurrent()
            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()
            controller.relays.add("wss://open.example", read = true, write = false)
            runCurrent()

            controller.logout()
            runCurrent()

            // The permissions were this account's consent, so they go with it.
            assertTrue(controller.relays.state.value.approved.isEmpty())
        }

    // ---- nothing is contacted during onboarding ---------------------------

    @Test
    fun `an account created while onboarding is up contacts nothing until it ends`() =
        runTest {
            val core = wayfarer()
            // The state a second run is in: relay grants are persisted and
            // survive both the session and the account, so by the time anybody
            // sees "Where should we start?" a second time, the app already has
            // somewhere it is allowed to go.
            core.relayDirectory.approve(relay("open.example"), read = true, write = false)
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            controller.createAccount()
            runCurrent()

            // The screen at this point says Wayfarer has not contacted anything
            // yet. That has to be true: permissions granted in some earlier
            // session are not an answer to the question still on screen.
            assertTrue(controller.onboarding.value is OnboardingStep.Backup)
            assertEquals(0, transport.startCount, "no client is brought up during onboarding")
            assertTrue(transport.fetched.isEmpty(), "and nothing is asked of anybody")

            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()

            // And on the far side of the question it is not dropped: what the
            // account has on the network is fetched once the user is in the app.
            assertTrue(transport.fetched.isNotEmpty(), "the load waits for onboarding, it does not vanish")
        }

    // ---- logging out ------------------------------------------------------

    @Test
    fun `logging out closes the sockets and shows the front door`() =
        runTest {
            val controller = browsingGuest(TestScope(testScheduler))
            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()
            assertTrue(transport.startCount > 0, "the session under test has to have opened something")

            controller.logout()
            runCurrent()

            // All three, because "logged out" that leaves the relays connected is
            // an account forgotten while the connections it opened carry on.
            assertNull(controller.account.value)
            assertEquals(OnboardingStep.Start, controller.onboarding.value)
            assertEquals(1, transport.stopCount, "logging out has to stop talking to relays")
        }

    @Test
    fun `logging out does not wait for whatever the app was doing`() =
        runTest {
            val controller = browsingGuest(TestScope(testScheduler))
            controller.createAccount()
            runCurrent()
            controller.finishBackup()
            controller.skipEntryPoint()
            runCurrent()

            // A load in flight is exactly when somebody reaches for Log out, and
            // it used to be the moment the button went dead: it was disabled on
            // the busy flag, and the screen change waited on the same coroutine
            // the flag belonged to.
            transport.holdFetches()
            controller.refreshFeed()
            runCurrent()

            controller.logout()

            // No runCurrent: the user is off the app on the same frame as the press.
            assertEquals(OnboardingStep.Start, controller.onboarding.value)

            transport.releaseFetches()
            runCurrent()
        }

    // ---- what the user is told is loading ---------------------------------

    @Test
    fun `a background reload never raises the busy indicator`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            controller.onEnterForeground()
            runCurrent()

            // Held open so the load is genuinely in flight while it is looked at.
            // Sampling afterwards proves nothing: busy is raised and cleared
            // inside the load, and StateFlow conflates the pair away.
            transport.holdFetches()
            controller.relays.add("wss://open.example", read = true, write = false)
            runCurrent()

            // Allowing a relay reloads the feed. That is background work the user
            // did not ask to wait for, and a progress bar reporting it is a
            // progress bar they learn to ignore.
            assertFalse(controller.busy.value, "a background reload must never raise the busy indicator")
            assertFalse(controller.refreshing.value)

            transport.releaseFetches()
            runCurrent()
        }

    @Test
    fun `signing in does not hold the progress bar while relays answer`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.relays.add("wss://open.example", read = true, write = false)
            runCurrent()

            // Held, so what a new account's own relays are doing is genuinely in
            // flight while the flag is looked at.
            transport.holdFetches()
            controller.createAccount()
            runCurrent()

            // The key exists and is on screen, which is what the press asked for.
            // What this account has out there is somebody else's servers taking
            // their time — and while that was held under the busy flag, every
            // onboarding button was disabled and Settings would not log out.
            assertTrue(controller.onboarding.value is OnboardingStep.Backup)
            assertFalse(
                controller.busy.value,
                "the bar must not stay up for relays answering after the account exists",
            )

            transport.releaseFetches()
            runCurrent()
        }

    @Test
    fun `an explicit refresh is the one feed load that shows itself`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            controller.onEnterForeground()
            controller.relays.add("wss://open.example", read = true, write = false)
            runCurrent()

            transport.holdFetches()
            controller.refreshFeed()
            assertTrue(controller.refreshing.value, "the pull-to-refresh indicator is for exactly this")
            runCurrent()
            assertTrue(controller.refreshing.value, "and stays up while the load is in flight")
            assertFalse(controller.busy.value, "a refresh is not the blocking kind of busy")

            transport.releaseFetches()
            runCurrent()
            assertFalse(controller.refreshing.value)
        }

    @Test
    fun `a burst of unknown authors costs one profile query, not one each`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()
            controller.continueWithoutAccount()
            controller.skipEntryPoint()
            runCurrent()
            controller.onEnterForeground()
            controller.relays.add("wss://open.example", read = true, write = false)
            runCurrent()

            val before = transport.profileQueries()
            repeat(12) { index ->
                transport.emit(
                    app.wayfarer.core.nostr.ReceivedEvent(
                        app.wayfarer.core.noteEvent(PubKey((10 + index).toString(16).padStart(2, '0').repeat(32).take(64)), "hi", createdAt = 500L + index, idSeed = 100 + index),
                        RelayUrl("wss://open.example/"),
                    ),
                )
            }
            runCurrent()
            // The batch window has to elapse before anything is asked for.
            advanceTimeBy(3_000)
            runCurrent()

            val queries = transport.profileQueries() - before
            assertTrue(queries in 1..2, "twelve strangers must not be twelve round trips, was $queries")
        }

    /** Fetch plans asking for kind 0 — i.e. profile lookups. */
    private fun FakeTransport.profileQueries(): Int =
        fetched.count { plan -> plan.values.any { filters -> filters.any { EventKind.METADATA in (it.kinds ?: emptyList()) } } }

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

    // ---- pictures, and the servers they would come from --------------------

    @Test
    fun `a profile's picture queues its server as the profile arrives`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            core.profiles.absorb(profileEvent(pubKey, "somebody", picture = "https://cdn.example.com/a.png"))
            runCurrent()

            // The complaint this answers: hosts used to reach the queue only when
            // somebody pressed the badge on a picture that had not loaded, so a
            // reader who never pressed one had an empty list and no pictures.
            assertEquals(
                listOf("cdn.example.com"),
                controller.media.state.value.pending.map { it.host.display() },
            )
        }

    @Test
    fun `a picture linked from a post queues its server too`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            core.feed.absorb(
                noteEvent(pubKey, "look at this https://img.example.com/cat.jpg", createdAt = 400, idSeed = 21),
                null,
            )
            runCurrent()

            val pending = controller.media.state.value.pending
            assertEquals(listOf("img.example.com"), pending.map { it.host.display() })
            // The reason is the whole decision: a bare host is not a question
            // anybody can answer.
            assertTrue(pending.first().reasons.any { it.source == MediaSource.POST_IMAGE }, "$pending")
        }

    @Test
    fun `queueing a host is not contacting it`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            core.profiles.absorb(profileEvent(pubKey, "somebody", picture = "https://cdn.example.com/a.png"))
            runCurrent()

            val host = controller.media.state.value.pending.first().host
            assertFalse(core.mediaDirectory.isApproved(host), "a queued host must have no grant")
        }

    @Test
    fun `a picture over plain http is refused rather than offered as a decision`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            core.profiles.absorb(profileEvent(pubKey, "somebody", picture = "http://cdn.example.com/a.png"))
            runCurrent()

            assertTrue(controller.media.state.value.pending.isEmpty())
        }

    // ---- payment targets (NIP-A3) ------------------------------------------

    @Test
    fun `somebody's payment addresses are read from their kind 10133`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            core.payments.absorb(paymentEvent(pubKey, "bitcoin" to "bc1qxq", "lightning" to "alice@example.com"))
            runCurrent()

            assertEquals(
                listOf(PaymentTarget("bitcoin", "bc1qxq"), PaymentTarget("lightning", "alice@example.com")),
                controller.paymentTargets.value[pubKey],
            )
        }

    @Test
    fun `publishing payment addresses is its own event, not part of the profile`() =
        runTest {
            val core = wayfarer()
            core.relayDirectory.approve(relay("write.example"), read = true, write = true)
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.createAccount()
            runCurrent()
            transport.published.clear()

            controller.savePaymentTargets(listOf(PaymentTarget("monero", "4Aabc")))
            runCurrent()

            val published = transport.published.single().first
            assertEquals(EventKind.PAYMENT_TARGETS, published.kind)
            assertEquals(listOf(listOf("payto", "monero", "4Aabc")), published.tags)
        }

    // ---- paging past the first page ---------------------------------------

    /**
     * The bug this covers: a relay answers a query with its *newest* events up
     * to the limit, so a profile showed one page and had no way to reach what
     * came before it however far the reader scrolled.
     */
    @Test
    fun `loading more asks for what came before the oldest post held`() =
        runTest {
            val core = wayfarer()
            core.relayDirectory.approve(relay("open.example"), read = true, write = false)
            core.feed.absorb(noteEvent(pubKey, "older", createdAt = 400, idSeed = 31), null)
            core.feed.absorb(noteEvent(pubKey, "newer", createdAt = 900, idSeed = 32), null)
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            transport.fetched.clear()

            controller.loadMoreFrom(pubKey)
            runCurrent()

            val filter = transport.fetched.last().values.first().first()
            // 399, not 400: NIP-01's `until` is inclusive, so starting at the
            // oldest post held would spend the whole page re-reading it.
            assertEquals(399, filter.until)
            assertEquals(listOf(pubKey.hex), filter.authors)
        }

    @Test
    fun `a page that brings nothing back retires the load more button`() =
        runTest {
            val core = wayfarer()
            core.relayDirectory.approve(relay("open.example"), read = true, write = false)
            core.feed.absorb(noteEvent(pubKey, "the only one", createdAt = 400, idSeed = 33), null)
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            controller.loadMoreFrom(pubKey)
            runCurrent()

            assertTrue(pubKey in controller.exhaustedAuthors.value)

            // And it stops asking: another press would be a fresh round of
            // queries to somebody else's relays for an answer already given.
            transport.fetched.clear()
            controller.loadMoreFrom(pubKey)
            runCurrent()
            assertTrue(transport.fetched.isEmpty())
        }

    @Test
    fun `opening a profile again offers to load more, in case they have posted since`() =
        runTest {
            val core = wayfarer()
            core.relayDirectory.approve(relay("open.example"), read = true, write = false)
            core.feed.absorb(noteEvent(pubKey, "the only one", createdAt = 400, idSeed = 34), null)
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()
            controller.loadMoreFrom(pubKey)
            runCurrent()

            controller.openProfile(pubKey)
            runCurrent()

            assertFalse(pubKey in controller.exhaustedAuthors.value)
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

    // ---- one event's own actions ------------------------------------------

    @Test
    fun `a post's own event can be shown raw and sent again`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            val event = noteEvent(pubKey(7), "hello", createdAt = 100)
            core.feed.absorb(event, relay("a.example"))

            // The projection has no signature or tags; the store keeps what did.
            assertEquals(event, controller.eventOf(event.id))
            val json = controller.rawJsonOf(event.id)
            assertNotNull(json)
            assertTrue(event.id.hex in json, "the raw event has to carry its own id")
        }

    @Test
    fun `an event nobody holds has no JSON rather than a crash`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            assertNull(controller.rawJsonOf(EventId("ab".repeat(32))))
            assertNull(controller.eventOf(EventId("ab".repeat(32))))
        }

    @Test
    fun `only write-approved relays are offered to send to`() =
        runTest {
            // Publishing is writing. A relay allowed only for getting posts was
            // not agreed to as somewhere this phone sends things.
            val core = wayfarer()
            val sendable = relay("send.example")
            val readOnly = relay("read.example")
            val blocked = relay("blocked.example")
            core.relayDirectory.approve(sendable, read = true, write = true)
            core.relayDirectory.approve(readOnly, read = true, write = false)
            core.relayDirectory.deny(blocked)

            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            assertEquals(listOf(sendable), controller.rebroadcastTargets())
        }

    @Test
    fun `rebroadcasting sends the same event to exactly the relays chosen`() =
        runTest {
            val core = wayfarer()
            val here = relay("here.example")
            val elsewhere = relay("elsewhere.example")
            core.relayDirectory.approve(here, read = true, write = true)
            core.relayDirectory.approve(elsewhere, read = true, write = true)

            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            val event = noteEvent(pubKey(7), "hello", createdAt = 100)
            core.feed.absorb(event, relay("a.example"))
            transport.published.clear()

            controller.rebroadcast(event.id, setOf(here))
            runCurrent()

            val (sent, relays) = transport.published.single()
            // Unchanged: nothing is re-signed, and nothing is rewritten.
            assertEquals(event, sent)
            assertEquals(setOf(here), relays)
        }

    @Test
    fun `rebroadcasting an event nobody holds reports it instead of throwing`() =
        runTest {
            val core = wayfarer()
            core.relayDirectory.approve(relay("here.example"), read = true, write = true)
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            controller.rebroadcast(EventId("ab".repeat(32)), setOf(relay("here.example")))
            runCurrent()

            assertTrue(controller.message.value is UserMessage.Error)
            assertTrue(transport.published.isEmpty())
        }

    @Test
    fun `rebroadcasting nowhere is refused rather than reported as sent`() =
        runTest {
            val core = wayfarer()
            val controller = AppController(core, TestScope(testScheduler))
            runCurrent()

            val event = noteEvent(pubKey(7), "hello", createdAt = 100)
            core.feed.absorb(event, relay("a.example"))
            transport.published.clear()

            controller.rebroadcast(event.id, emptySet())
            runCurrent()

            assertTrue(controller.message.value is UserMessage.Error)
            assertTrue(transport.published.isEmpty())
        }

    // ---- conversations ----------------------------------------------------

    @Test
    fun `folding a reply is remembered and folding it again undoes it`() =
        runTest {
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            val reply = EventId("ab".repeat(32))
            assertTrue(controller.threads.collapsed.value.isEmpty())

            controller.threads.toggleCollapsed(reply)
            assertTrue(reply in controller.threads.collapsed.value)

            controller.threads.toggleCollapsed(reply)
            assertFalse(reply in controller.threads.collapsed.value)
        }

    @Test
    fun `folds accumulate rather than replacing each other`() =
        runTest {
            // Two folded replies must both stay folded: reading a long thread
            // means folding several branches, and having each one spring the
            // last back open would make that impossible.
            val controller = AppController(wayfarer(), TestScope(testScheduler))
            runCurrent()

            val first = EventId("ab".repeat(32))
            val second = EventId("cd".repeat(32))
            val untouched = EventId("ef".repeat(32))

            controller.threads.toggleCollapsed(first)
            controller.threads.toggleCollapsed(second)

            assertEquals(setOf(first, second), controller.threads.collapsed.value)
            assertFalse(untouched in controller.threads.collapsed.value)

            // Unfolding one leaves the other alone.
            controller.threads.toggleCollapsed(first)
            assertEquals(setOf(second), controller.threads.collapsed.value)
        }
}
