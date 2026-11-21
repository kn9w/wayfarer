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

/**
 * Storage for account secret keys. Backed by the platform keystore.
 *
 * Keyed by account, because more than one can be signed in at a time: [id] is
 * the owning pubkey's hex. A single slot would mean adding a second account
 * overwrote the first one's key, which is the one kind of data loss this app
 * cannot apologise its way out of.
 */
interface SecretStore {
    suspend fun readSecKeyHex(id: String): String?

    suspend fun writeSecKeyHex(
        id: String,
        secKeyHex: String,
    )

    suspend fun clear(id: String)

    /**
     * The single unkeyed slot builds before multi-account wrote, or null.
     *
     * Read once at startup and moved under its owner's id — see
     * `AccountManager.restore`. A key is the one thing that may never be
     * silently dropped in a storage change, so this migration exists where the
     * relay and media directories deliberately have none.
     */
    suspend fun readLegacySecKeyHex(): String? = null

    /** Removes the legacy slot once its key has been rewritten under an id. */
    suspend fun clearLegacy() = Unit
}
