package app.wayfarer.core

import app.wayfarer.core.repo.LocalFollowStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The follow list that is never published.
 *
 * The counterpart to the relay permission list: nothing here produces an event
 * or reaches a relay, and it belongs to the account that made it.
 */
class LocalFollowStoreTest {
    private val alice = pubKey(1)
    private val bob = pubKey(2)
    private val me = pubKey(9)
    private val somebodyElse = pubKey(8)

    @Test
    fun `following and unfollowing round-trips through storage`() =
        runTest {
            val settings = FakeKeyValueStore()
            val store = LocalFollowStore(settings)
            store.load(me)

            store.add(alice)
            store.add(bob)
            assertEquals(setOf(alice, bob), store.follows.value)

            store.remove(alice)
            assertEquals(setOf(bob), store.follows.value)

            // A second store over the same settings sees what the first wrote.
            val reopened = LocalFollowStore(settings)
            reopened.load(me)
            assertEquals(setOf(bob), reopened.follows.value)
        }

    @Test
    fun `the list belongs to one account and is not inherited by another`() =
        runTest {
            val settings = FakeKeyValueStore()
            val store = LocalFollowStore(settings)

            store.load(me)
            store.add(alice)

            store.load(somebodyElse)
            assertTrue(store.follows.value.isEmpty(), "signing in as somebody else must not inherit a list")

            // And the first account's list is still there, untouched.
            store.load(me)
            assertEquals(setOf(alice), store.follows.value)
        }

    @Test
    fun `signing out forgets the list without deleting it`() =
        runTest {
            val settings = FakeKeyValueStore()
            val store = LocalFollowStore(settings)
            store.load(me)
            store.add(alice)

            store.clear()
            assertTrue(store.follows.value.isEmpty())

            store.load(me)
            assertEquals(setOf(alice), store.follows.value, "signing back in brings the list back")
        }

    @Test
    fun `an unreadable line is skipped rather than failing the load`() =
        runTest {
            // The rule the relay directory codec documents: a field written by a
            // later build must not lock somebody out of their own list.
            val settings = FakeKeyValueStore()
            settings.putString("follows.local.${me.hex}", "not a pubkey\n${alice.hex}\n\n???")
            val store = LocalFollowStore(settings)

            store.load(me)

            assertEquals(setOf(alice), store.follows.value)
        }

    @Test
    fun `with nobody signed in a follow is not silently kept in memory`() =
        runTest {
            // No account means no key to file it under. Holding it in memory
            // would show a follow that vanishes on the next launch.
            val store = LocalFollowStore(FakeKeyValueStore())

            store.add(alice)

            assertTrue(store.follows.value.isEmpty())
        }

    @Test
    fun `following somebody twice does not write twice`() =
        runTest {
            val settings = FakeKeyValueStore()
            val store = LocalFollowStore(settings)
            store.load(me)

            store.add(alice)
            val writes = settings.writes
            store.add(alice)

            assertEquals(writes, settings.writes, "a follow already held is not a change")
        }
}
