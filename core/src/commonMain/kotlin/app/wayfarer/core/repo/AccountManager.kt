package app.wayfarer.core.repo

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.nostr.Bech32Codec
import app.wayfarer.core.nostr.EventSigner
import app.wayfarer.core.nostr.KeyTool
import app.wayfarer.core.store.KeyValueStore
import app.wayfarer.core.store.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// kotlin.jvm.Volatile is a default import on JVM only; the explicit one keeps
// commonMain compiling if a native target is ever added.
import kotlin.concurrent.Volatile

/** Who is signed in. A watch-only account can read everything and publish nothing. */
data class Account(
    val pubKey: PubKey,
    val npub: String,
    val canSign: Boolean,
)

/** Builds a signer for an account. Watch-only when [secKeyHex] is null. */
fun interface SignerFactory {
    fun create(
        pubKey: PubKey,
        secKeyHex: String?,
    ): EventSigner
}

sealed interface LoginResult {
    data class Success(
        val account: Account,
    ) : LoginResult

    data object NotAKey : LoginResult
}

/**
 * Account creation, login and logout.
 *
 * The secret key never leaves [SecretStore] except to build a signer and to be
 * shown once, on request, in the backup screen. Nothing here logs it and
 * [app.wayfarer.core.model.SecKey.toString] redacts it.
 */
class AccountManager(
    private val keyTool: KeyTool,
    private val bech32: Bech32Codec,
    private val secrets: SecretStore,
    private val settings: KeyValueStore,
    private val signerFactory: SignerFactory,
) {
    private val state = MutableStateFlow<Account?>(null)

    val account: StateFlow<Account?> = state.asStateFlow()

    @Volatile
    private var currentSigner: EventSigner? = null

    /** The signer for the signed-in account, or null when signed out. */
    val signer: EventSigner? get() = currentSigner

    /** Restores the previous session. Call once at startup. */
    suspend fun restore(): Account? {
        val storedPubKey = PubKey.parseOrNull(settings.getString(KEY_PUBKEY)) ?: return null
        val secKeyHex = secrets.readSecKeyHex()
        // A stored secret that no longer matches the stored pubkey means the two
        // stores disagree; trust the secret, since that is the thing that can sign.
        val pubKey = secKeyHex?.let(keyTool::pubKeyOf) ?: storedPubKey
        return activate(pubKey, secKeyHex)
    }

    /**
     * Generates a brand-new identity and signs in with it.
     *
     * Returns the nsec so the UI can show it exactly once for backup; it is not
     * returned again afterwards without an explicit [revealSecretKey].
     */
    suspend fun createAccount(): Pair<Account, String> {
        val secKeyHex = keyTool.generateSecKeyHex()
        val account = activate(keyTool.pubKeyOf(secKeyHex), secKeyHex)
        return account to bech32.encodeNsec(secKeyHex)
    }

    /** Signs in from an `nsec…` (full access) or `npub…` (watch-only). Hex is accepted too. */
    suspend fun login(input: String): LoginResult {
        val trimmed = input.trim()

        bech32.decodeSecKeyHex(trimmed)?.let { secKeyHex ->
            return LoginResult.Success(activate(keyTool.pubKeyOf(secKeyHex), secKeyHex))
        }
        bech32.decodePubKey(trimmed)?.let { pubKey ->
            return LoginResult.Success(activate(pubKey, secKeyHex = null))
        }
        return LoginResult.NotAKey
    }

    suspend fun logout() {
        secrets.clear()
        settings.remove(KEY_PUBKEY)
        currentSigner = null
        state.value = null
    }

    /** The account's nsec, for the backup screen. Null for a watch-only account. */
    suspend fun revealSecretKey(): String? = secrets.readSecKeyHex()?.let(bech32::encodeNsec)

    private suspend fun activate(
        pubKey: PubKey,
        secKeyHex: String?,
    ): Account {
        if (secKeyHex != null) secrets.writeSecKeyHex(secKeyHex) else secrets.clear()
        settings.putString(KEY_PUBKEY, pubKey.hex)

        currentSigner = signerFactory.create(pubKey, secKeyHex)
        val account = Account(pubKey, bech32.encodeNpub(pubKey), canSign = secKeyHex != null)
        state.value = account
        return account
    }

    private companion object {
        const val KEY_PUBKEY = "account.pubkey"
    }
}
