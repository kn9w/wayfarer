package app.wayfarer.core

import app.wayfarer.core.relay.RelayDirectory
import app.wayfarer.core.store.PersistedRelayDirectoryStore
import app.wayfarer.core.store.RelayDirectoryCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whose relay permissions these are.
 *
 * The list used to be one record for the device, kept forever: every account
 * that ever signed in shared it, a new account inherited whatever the last user
 * of the phone had allowed, and signing out left it behind. It is a record of
 * consent given by a person, so it is keyed by that person — the same rule
 * [app.wayfarer.core.repo.LocalFollowStore] has always followed.
 */
class RelayPermissionScopeTest {
    private val clock = FakeClock()
    private val alice = pubKey(1)
    private val bob = pubKey(2)

    private fun directory(settings: FakeKeyValueStore) =
        RelayDirectory(
            clock = clock,
            persistence = PersistedRelayDirectoryStore(settings, RelayDirectoryCodec(testNormalizer)),
        )

    @Test
    fun `one account does not inherit another's permissions`() =
        runTest {
            val settings = FakeKeyValueStore()
            val directory = directory(settings)

            directory.scopeTo(alice)
            directory.approve(relay("alice.example"), read = true, write = false)

            directory.scopeTo(bob)

            assertTrue(directory.grants.isEmpty(), "signing in as somebody else starts with nothing allowed")
            assertFalse(directory.isApproved(relay("alice.example")))
        }

    @Test
    fun `an account's permissions come back when they sign in again`() =
        runTest {
            val settings = FakeKeyValueStore()
            val directory = directory(settings)

            directory.scopeTo(alice)
            directory.approve(relay("alice.example"), read = true, write = true)
            // Signing out, then in again — and, since the store is the same
            // settings map, a fresh launch would read exactly the same thing.
            directory.scopeTo(null)
            directory.scopeTo(alice)

            assertEquals(setOf(relay("alice.example")), directory.grants.keys)
            assertTrue(directory.canWrite(relay("alice.example")), "and with the same permissions, not a downgrade")
        }

    @Test
    fun `a session with nobody signed in keeps nothing`() =
        runTest {
            val settings = FakeKeyValueStore()
            val directory = directory(settings)

            directory.scopeTo(null)
            directory.approve(relay("guest.example"), read = true, write = false)

            // It works for this session — reading without an account is a
            // supported way to use the app, so the permission has to hold.
            assertTrue(directory.isApproved(relay("guest.example")))
            // And it is written down nowhere, so the next launch is asked again
            // rather than talking to a relay nobody in this session approved.
            assertTrue(settings.values.isEmpty(), "a guest's consent is not a record to keep")

            directory.scopeTo(null)
            assertTrue(directory.grants.isEmpty())
        }

    @Test
    fun `signing out forgets the list without deleting it`() =
        runTest {
            val settings = FakeKeyValueStore()
            val directory = directory(settings)

            directory.scopeTo(alice)
            directory.approve(relay("alice.example"), read = true, write = false)
            directory.scopeTo(null)

            assertTrue(directory.grants.isEmpty(), "a signed-out session may talk to nothing")
            assertTrue(
                settings.values.values.any { "alice.example" in it },
                "but the record survives, so signing back in restores it",
            )
        }

    @Test
    fun `the device-wide list a previous build kept is not adopted by anybody`() =
        runTest {
            val settings = FakeKeyValueStore()
            // What the old build wrote: one key, no owner.
            settings.values["relay.directory.v1"] = "G\twss://inherited.example/\ttrue\ttrue\n"

            val directory = directory(settings)
            directory.scopeTo(alice)

            // Not a migration this app can make honestly. That list is the
            // consent of whoever was using the phone, which is not a fact about
            // this account — so it is left where it is and Alice is asked.
            assertTrue(directory.grants.isEmpty())
        }
}
