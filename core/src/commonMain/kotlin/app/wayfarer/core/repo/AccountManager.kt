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

/**
 * How the signed-in account can sign, if at all.
 *
 * Three shapes rather than a nullable key, because an external signer holds no
 * key here but can still publish — a distinction a `secKeyHex: String?` cannot
 * express.
 */
sealed interface Credential {
    /** A key held by this app, encrypted at rest. */
    data class LocalKey(
        val secKeyHex: String,
    ) : Credential

    /** A NIP-55 Android signer app. This app never sees the key. */
    data class ExternalSigner(
        val packageName: String,
    ) : Credential

    /** Logged in with an npub: reads everything, publishes nothing. */
    data object WatchOnly : Credential
}

/** Who is signed in. */
data class Account(
    val pubKey: PubKey,
    val npub: String,
    val credential: Credential,
) {
    val canSign: Boolean get() = credential !is Credential.WatchOnly

    /** True when the key lives in this app and can be shown for backup. */
    val hasLocalKey: Boolean get() = credential is Credential.LocalKey
}

/**
 * Builds a signer for an account.
 *
 * The NIP-55 case is implemented in the Android layer rather than the nostr
 * backend, because signing there means launching an activity. That is the whole
 * reason [EventSigner] is an interface.
 */
fun interface SignerFactory {
    fun create(
        pubKey: PubKey,
        credential: Credential,
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

        val signerPackage = settings.getString(KEY_SIGNER_PACKAGE)?.takeIf { it.isNotBlank() }
        val credential =
            when {
                secKeyHex != null -> Credential.LocalKey(secKeyHex)
                signerPackage != null -> Credential.ExternalSigner(signerPackage)
                else -> Credential.WatchOnly
            }

        // A stored secret that no longer matches the stored pubkey means the two
        // stores disagree; trust the secret, since that is the thing that can sign.
        val pubKey = secKeyHex?.let(keyTool::pubKeyOf) ?: storedPubKey
        return activate(pubKey, credential)
    }

    /**
     * Generates a brand-new identity and signs in with it.
     *
     * Returns the nsec so the UI can show it exactly once for backup; it is not
     * returned again afterwards without an explicit [revealSecretKey].
     */
    suspend fun createAccount(): Pair<Account, String> {
        val secKeyHex = keyTool.generateSecKeyHex()
        val account = activate(keyTool.pubKeyOf(secKeyHex), Credential.LocalKey(secKeyHex))
        return account to bech32.encodeNsec(secKeyHex)
    }

    /** Signs in from an `nsec…` (full access) or `npub…` (watch-only). Hex is accepted too. */
    suspend fun login(input: String): LoginResult {
        val trimmed = input.trim()

        bech32.decodeSecKeyHex(trimmed)?.let { secKeyHex ->
            return LoginResult.Success(activate(keyTool.pubKeyOf(secKeyHex), Credential.LocalKey(secKeyHex)))
        }
        bech32.decodePubKey(trimmed)?.let { pubKey ->
            return LoginResult.Success(activate(pubKey, Credential.WatchOnly))
        }
        return LoginResult.NotAKey
    }

    /**
     * Signs in with a NIP-55 signer app, which has already told us the user's
     * pubkey and its own package name. No key material is involved.
     */
    suspend fun loginWithExternalSigner(
        pubKey: PubKey,
        packageName: String,
    ): Account = activate(pubKey, Credential.ExternalSigner(packageName))

    suspend fun logout() {
        secrets.clear()
        settings.remove(KEY_PUBKEY)
        settings.remove(KEY_SIGNER_PACKAGE)
        currentSigner = null
        state.value = null
    }

    /** The account's nsec, for the backup screen. Null for a watch-only account. */
    suspend fun revealSecretKey(): String? = secrets.readSecKeyHex()?.let(bech32::encodeNsec)

    private suspend fun activate(
        pubKey: PubKey,
        credential: Credential,
    ): Account {
        when (credential) {
            is Credential.LocalKey -> secrets.writeSecKeyHex(credential.secKeyHex)
            else -> secrets.clear()
        }
        settings.putString(KEY_PUBKEY, pubKey.hex)
        when (credential) {
            is Credential.ExternalSigner -> settings.putString(KEY_SIGNER_PACKAGE, credential.packageName)
            else -> settings.remove(KEY_SIGNER_PACKAGE)
        }

        currentSigner = signerFactory.create(pubKey, credential)
        val account = Account(pubKey, bech32.encodeNpub(pubKey), credential)
        state.value = account
        return account
    }

    private companion object {
        const val KEY_PUBKEY = "account.pubkey"
        const val KEY_SIGNER_PACKAGE = "account.signer.package"
    }
}
