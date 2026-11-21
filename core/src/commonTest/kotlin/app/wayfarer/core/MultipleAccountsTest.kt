package app.wayfarer.core

import app.wayfarer.core.repo.AccountManager
import app.wayfarer.core.repo.Credential
import app.wayfarer.core.repo.CredentialKind
import app.wayfarer.core.repo.LoginResult
import app.wayfarer.core.repo.SignerFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * More than one account signed in at a time.
 *
 * Nostr identities are cheap and people keep several on purpose. An app that
 * holds one makes using the second mean destroying the first — which, for an
 * account whose key lives here, means erasing the only copy of that key.
 */
class MultipleAccountsTest {
    private val settings = FakeKeyValueStore()
    private val secrets = FakeSecretStore()

    private var seed = 0

    private fun accounts() =
        AccountManager(
            keyTool = FakeKeyTool { ++seed },
            bech32 = FakeBech32Codec,
            secrets = secrets,
            settings = settings,
            signerFactory = SignerFactory { pubKey, credential -> FakeSigner(pubKey, credential !is Credential.WatchOnly) },
        )

    @Test
    fun `a second account does not evict the first`() =
        runTest {
            val manager = accounts()
            val first = manager.createAccount().first
            val second = manager.createAccount().first

            assertEquals(second.pubKey, manager.account.value?.pubKey, "the newest sign-in is the active one")
            assertEquals(
                setOf(first.pubKey, second.pubKey),
                manager.accounts.value.mapTo(mutableSetOf()) { it.pubKey },
                "and the first is still signed in",
            )
            // The thing that used to be destroyed: one slot meant the second
            // account's key overwrote the first account's.
            assertNotNull(secrets.readSecKeyHex(first.pubKey.hex), "the first account's key survives the second")
        }

    @Test
    fun `switching makes the other account active without touching either`() =
        runTest {
            val manager = accounts()
            val first = manager.createAccount().first
            manager.createAccount()

            val back = manager.switchTo(first.pubKey)

            assertEquals(first.pubKey, back?.pubKey)
            assertEquals(first.pubKey, manager.account.value?.pubKey)
            assertTrue(back?.canSign == true, "and it comes back able to sign, not as a read-only shell")
            assertEquals(2, manager.accounts.value.size, "switching signs nobody out")
        }

    @Test
    fun `logging out hands the app to whoever else is signed in`() =
        runTest {
            val manager = accounts()
            val first = manager.createAccount().first
            val second = manager.createAccount().first

            val next = manager.logout()

            assertEquals(first.pubKey, next?.pubKey, "leaving the second identity puts you back in the first")
            assertEquals(listOf(first.pubKey), manager.accounts.value.map { it.pubKey })
            assertNull(secrets.readSecKeyHex(second.pubKey.hex), "and the key of the account that left is erased")
        }

    @Test
    fun `logging out of the last account leaves nobody signed in`() =
        runTest {
            val manager = accounts()
            manager.createAccount()

            assertNull(manager.logout())
            assertNull(manager.account.value)
            assertTrue(manager.accounts.value.isEmpty())
        }

    @Test
    fun `the active account and the whole list survive a restart`() =
        runTest {
            val first = accounts().let { manager ->
                val account = manager.createAccount().first
                manager.createAccount()
                manager.switchTo(account.pubKey)
                account
            }

            // A new manager over the same stores is what a relaunch looks like.
            val restored = accounts().restore()

            assertEquals(first.pubKey, restored?.pubKey, "the account that was active is the one that comes back")
            assertTrue(restored?.canSign == true)
        }

    @Test
    fun `signing in again with a key already here is a re-login, not a duplicate`() =
        runTest {
            val manager = accounts()
            val account = manager.createAccount().first
            val nsec = secrets.readSecKeyHex(account.pubKey.hex)!!

            // The npub first — a watch-only view of an account whose key is here.
            manager.login(FakeBech32Codec.encodeNpub(account.pubKey))
            val result = manager.login(FakeBech32Codec.encodeNsec(nsec))

            assertTrue(result is LoginResult.Success)
            assertEquals(1, manager.accounts.value.size, "one identity is one row, however many ways it was added")
            assertEquals(CredentialKind.LocalKey, manager.accounts.value.single().kind, "and the upgrade sticks")
        }

    @Test
    fun `the single account an older build stored is carried across`() =
        runTest {
            // What the old layout looked like: one unkeyed secret and a pubkey.
            val key = FakeKeyTool { 7 }.generateSecKeyHex()
            val owner = FakeKeyTool { 7 }.pubKeyOf(key)
            secrets.legacySecKeyHex = key
            settings.values["account.pubkey"] = owner.hex

            val restored = accounts().restore()

            // A key is the one thing a storage change may never silently drop.
            assertEquals(owner, restored?.pubKey)
            assertTrue(restored?.hasLocalKey == true)
            assertEquals(key, secrets.readSecKeyHex(owner.hex), "moved under its owner's id")
            assertNull(secrets.legacySecKeyHex, "and the old slot cleared")
        }
}
