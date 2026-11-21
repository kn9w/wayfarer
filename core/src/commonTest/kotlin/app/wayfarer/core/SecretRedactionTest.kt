package app.wayfarer.core

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.SecKey
import app.wayfarer.core.repo.Account
import app.wayfarer.core.repo.Credential
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The secret key must not be printable.
 *
 * [SecKey] exists so that a key cannot reach a log line, a crash report or an
 * interpolated error message by accident. That only holds if the types actually
 * carrying the key use it: a `data class` with a bare `String` field generates a
 * [toString] containing the whole secret, and the guard rail becomes a comment.
 *
 * Pinned as a test rather than trusted to review because the failure mode is
 * silent — nothing breaks, the key is simply in the string — and because it
 * depends on the compiler honouring a value class's [toString] override inside a
 * generated one.
 */
class SecretRedactionTest {
    private val hex = "ab".repeat(32)

    @Test
    fun `SecKey does not print its key`() {
        val text = SecKey(hex).toString()

        assertFalse(hex in text, "SecKey.toString leaked the key: $text")
        assertTrue("redacted" in text)
    }

    @Test
    fun `a local-key credential does not print its key`() {
        val text = Credential.LocalKey(SecKey(hex)).toString()

        assertFalse(hex in text, "Credential.LocalKey.toString leaked the key: $text")
    }

    @Test
    fun `an account does not print the key it holds`() {
        val account =
            Account(
                pubKey = PubKey("cd".repeat(32)),
                npub = "npub1example",
                credential = Credential.LocalKey(SecKey(hex)),
            )

        // Account is the type most likely to be interpolated into a message, since
        // it is what the whole app passes around to mean "who is signed in".
        assertFalse(hex in account.toString(), "Account.toString leaked the key: $account")
    }
}
