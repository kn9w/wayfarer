package app.wayfarer.android.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.wayfarer.core.store.KeyValueStore
import app.wayfarer.core.store.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ordinary settings: the relay directory and the signed-in pubkey. Not secret. */
class AndroidKeyValueStore(
    context: Context,
) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("wayfarer.settings", Context.MODE_PRIVATE)

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) { prefs.getString(key, null) }

    override suspend fun putString(
        key: String,
        value: String,
    ) = withContext(Dispatchers.IO) { prefs.edit().putString(key, value).apply() }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) { prefs.edit().remove(key).apply() }
}

/**
 * The account's secret key, encrypted with a hardware-backed AES key that never
 * leaves the Android keystore.
 *
 * Written directly against `AndroidKeyStore` rather than pulling in
 * `androidx.security:security-crypto`: that library is deprecated, and this is
 * about sixty lines of standard JCA. For an app whose stated goal is the fewest
 * dependencies possible, a dependency for AES-GCM is not worth it.
 *
 * Note what this does and does not buy. The ciphertext on disk is useless without
 * the keystore key, so an attacker holding the app's files alone cannot recover
 * the nsec. An attacker running code as this app on an unlocked device can ask
 * the keystore to decrypt, as they could with any at-rest scheme that does not
 * prompt the user. Requiring authentication per decryption would close that and
 * is a one-line change to the [KeyGenParameterSpec] below.
 */
class AndroidSecretStore(
    context: Context,
) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences("wayfarer.secrets", Context.MODE_PRIVATE)

    override suspend fun readSecKeyHex(id: String): String? =
        withContext(Dispatchers.IO) {
            val stored = prefs.getString(entryFor(id), null) ?: return@withContext null
            runCatching { decrypt(stored) }.getOrNull()
        }

    override suspend fun writeSecKeyHex(
        id: String,
        secKeyHex: String,
    ) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(entryFor(id), encrypt(secKeyHex)).apply()
        }
    }

    override suspend fun clear(id: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(entryFor(id)).apply()
            deleteKeystoreKeyIfUnused()
        }
    }

    override suspend fun readLegacySecKeyHex(): String? =
        withContext(Dispatchers.IO) {
            val stored = prefs.getString(LEGACY_ENTRY, null) ?: return@withContext null
            runCatching { decrypt(stored) }.getOrNull()
        }

    override suspend fun clearLegacy() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(LEGACY_ENTRY).apply()
        }
    }

    private fun entryFor(id: String) = "$ENTRY_PREFIX$id"

    /**
     * Drops the keystore key only when nothing is left encrypted under it.
     *
     * One AES key protects every account's ciphertext, so deleting it on any one
     * logout would destroy the keys of the accounts still signed in. This is
     * what makes logging out of one account safe for the others.
     */
    private fun deleteKeystoreKeyIfUnused() {
        val anyLeft = prefs.all.keys.any { it.startsWith(ENTRY_PREFIX) || it == LEGACY_ENTRY }
        if (!anyLeft) runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // iv || ciphertext, so one opaque string is all that has to be stored.
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun secretKey(): SecretKey {
        val existing = keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        existing?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // .setUserAuthenticationRequired(true) would require a device
                // unlock or biometric for every read of the nsec.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "wayfarer.account.seckey"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** One entry per account, named by its pubkey hex. */
        const val ENTRY_PREFIX = "account.seckey."

        /** The single slot builds before multi-account wrote. Migrated, then removed. */
        const val LEGACY_ENTRY = "account.seckey"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
