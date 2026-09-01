package app.wayfarer.core.store

/**
 * Minimal persistence, implemented per platform.
 *
 * Two stores rather than one because they have different threat models:
 * [KeyValueStore] holds settings and the relay directory, while [SecretStore]
 * holds the account's secret key and is expected to be backed by the platform
 * keystore. Keeping them apart means no code path can put a secret in the
 * ordinary store by accident.
 */
interface KeyValueStore {
    suspend fun getString(key: String): String?

    suspend fun putString(
        key: String,
        value: String,
    )

    suspend fun remove(key: String)
}

/** Storage for the account secret key. Backed by the platform keystore. */
interface SecretStore {
    suspend fun readSecKeyHex(): String?

    suspend fun writeSecKeyHex(secKeyHex: String)

    suspend fun clear()
}
