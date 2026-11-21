package app.wayfarer.core.repo

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.SecKey
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
    /**
     * A key held by this app, encrypted at rest.
     *
     * Typed [SecKey] rather than [String] so the generated [toString] of this
     * class — and of [Account], which holds one — cannot print the key. A data
     * class whose field is a bare hex string puts the whole secret into every
     * log line, crash report and error message that ever interpolates an
     * account, which is exactly the accident [SecKey] exists to prevent.
     */
    data class LocalKey(
        val secKey: SecKey,
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
 * How an account signs, without the material that lets it.
 *
 * The roster of signed-in accounts is a list of these rather than of [Account],
 * so the keys of accounts nobody is currently using are not sitting decrypted in
 * memory. One is materialised into a [Credential] at the moment it becomes
 * active, and put away again when another does.
 */
enum class CredentialKind {
    /** A key held by this app for this account. */
    LocalKey,

    /** A NIP-55 signer app holds it. */
    ExternalSigner,

    /** An npub: everything readable, nothing publishable. */
    WatchOnly,
}

/** One signed-in identity, as the account list knows it. */
data class AccountSummary(
    val pubKey: PubKey,
    val npub: String,
    val kind: CredentialKind,
    /** The signer app's package, for [CredentialKind.ExternalSigner]. */
    val signerPackage: String? = null,
)

/**
 * Accounts: signing in, switching between them, and leaving.
 *
 * More than one account can be signed in at a time, and exactly one is active.
 * That is not a convenience feature bolted on: nostr identities are cheap and
 * people keep several on purpose — a name, a pseudonym, a project — and an app
 * that can hold only one makes using the second mean destroying the first,
 * which for a `LocalKey` account means erasing the only copy of a key.
 *
 * The secret key never leaves [SecretStore] except to build a signer and to be
 * shown once, on request, in the backup screen. Nothing here logs it, and it is
 * carried as a [SecKey] — whose [SecKey.toString] redacts it — so that it cannot
 * reach a log line through the generated [toString] of the types holding it.
 * Only the active account's key is ever read.
 */
class AccountManager(
    private val keyTool: KeyTool,
    private val bech32: Bech32Codec,
    private val secrets: SecretStore,
    private val settings: KeyValueStore,
    private val signerFactory: SignerFactory,
) {
    private val state = MutableStateFlow<Account?>(null)
    private val roster = MutableStateFlow<List<AccountSummary>>(emptyList())

    val account: StateFlow<Account?> = state.asStateFlow()

    /** Every account signed in on this device, active one included. */
    val accounts: StateFlow<List<AccountSummary>> = roster.asStateFlow()

    @Volatile
    private var currentSigner: EventSigner? = null

    /** The signer for the signed-in account, or null when signed out. */
    val signer: EventSigner? get() = currentSigner

    /** Restores the previous session. Call once at startup. */
    suspend fun restore(): Account? {
        migrateSingleAccount()

        roster.value = decodeRoster(settings.getString(KEY_ROSTER))
        val wanted = PubKey.parseOrNull(settings.getString(KEY_ACTIVE))
        val summary = roster.value.firstOrNull { it.pubKey == wanted } ?: roster.value.firstOrNull() ?: return null
        return activate(summary)
    }

    /**
     * Generates a brand-new identity and signs in with it.
     *
     * Returns the nsec so the UI can show it exactly once for backup; it is not
     * returned again afterwards without an explicit [revealSecretKey].
     */
    suspend fun createAccount(): Pair<Account, String> {
        val secKeyHex = keyTool.generateSecKeyHex()
        val account = add(keyTool.pubKeyOf(secKeyHex), Credential.LocalKey(SecKey(secKeyHex)))
        return account to bech32.encodeNsec(secKeyHex)
    }

    /** Signs in from an `nsec…` (full access) or `npub…` (watch-only). Hex is accepted too. */
    suspend fun login(input: String): LoginResult {
        val trimmed = input.trim()

        bech32.decodeSecKeyHex(trimmed)?.let { secKeyHex ->
            return LoginResult.Success(add(keyTool.pubKeyOf(secKeyHex), Credential.LocalKey(SecKey(secKeyHex))))
        }
        bech32.decodePubKey(trimmed)?.let { pubKey ->
            return LoginResult.Success(add(pubKey, Credential.WatchOnly))
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
    ): Account = add(pubKey, Credential.ExternalSigner(packageName))

    /**
     * Makes an account that is already signed in the active one.
     *
     * Null when this device knows no such account, which is a caller bug rather
     * than a user error — the list it chose from is this class's own.
     */
    suspend fun switchTo(pubKey: PubKey): Account? {
        if (state.value?.pubKey == pubKey) return state.value
        val summary = roster.value.firstOrNull { it.pubKey == pubKey } ?: return null
        return activate(summary)
    }

    /**
     * Signs the active account out and hands back whoever is active now.
     *
     * A departure rather than a pause: the key is erased, and the caller erases
     * the permissions granted under it. Another signed-in account takes over if
     * there is one, so logging out of a second identity puts you back in the
     * first rather than out of the app.
     */
    suspend fun logout(): Account? {
        val leaving = state.value ?: return null

        secrets.clear(leaving.pubKey.hex)
        roster.value = roster.value.filterNot { it.pubKey == leaving.pubKey }
        settings.putString(KEY_ROSTER, encodeRoster(roster.value))

        currentSigner = null
        state.value = null

        val next = roster.value.firstOrNull()
        if (next == null) {
            settings.remove(KEY_ACTIVE)
            return null
        }
        return activate(next)
    }

    /** The account's nsec, for the backup screen. Null unless it holds a local key. */
    suspend fun revealSecretKey(): String? {
        val active = state.value ?: return null
        return secrets.readSecKeyHex(active.pubKey.hex)?.let(bech32::encodeNsec)
    }

    /** Adds an account to the roster and makes it active. */
    private suspend fun add(
        pubKey: PubKey,
        credential: Credential,
    ): Account {
        if (credential is Credential.LocalKey) secrets.writeSecKeyHex(pubKey.hex, credential.secKey.hex)

        val summary =
            AccountSummary(
                pubKey = pubKey,
                npub = bech32.encodeNpub(pubKey),
                kind = credential.kind(),
                signerPackage = (credential as? Credential.ExternalSigner)?.packageName,
            )
        // Replaced rather than duplicated: signing in again with a key already
        // here is a re-login, and it may have changed how it signs — an npub
        // upgraded to its nsec, say.
        roster.value = roster.value.filterNot { it.pubKey == pubKey } + summary
        settings.putString(KEY_ROSTER, encodeRoster(roster.value))

        return makeActive(pubKey, credential)
    }

    /** Materialises a summary's credential and makes it active. */
    private suspend fun activate(summary: AccountSummary): Account {
        val credential =
            when (summary.kind) {
                CredentialKind.LocalKey ->
                    secrets
                        .readSecKeyHex(summary.pubKey.hex)
                        ?.let(SecKey::parseOrNull)
                        ?.let(Credential::LocalKey)
                        // The record says there is a key and the keystore
                        // disagrees. Watch-only is the honest fallback: the
                        // account still reads, and nothing pretends it can post.
                        ?: Credential.WatchOnly
                CredentialKind.ExternalSigner ->
                    summary.signerPackage?.let(Credential::ExternalSigner) ?: Credential.WatchOnly
                CredentialKind.WatchOnly -> Credential.WatchOnly
            }
        return makeActive(summary.pubKey, credential)
    }

    private suspend fun makeActive(
        pubKey: PubKey,
        credential: Credential,
    ): Account {
        settings.putString(KEY_ACTIVE, pubKey.hex)
        currentSigner = signerFactory.create(pubKey, credential)
        val account = Account(pubKey, bech32.encodeNpub(pubKey), credential)
        state.value = account
        return account
    }

    /**
     * Adopts the single account builds before this one stored.
     *
     * The key is the one thing a storage change may not silently drop, so unlike
     * the relay and media directories — whose old records deliberately belong to
     * nobody and are left behind — this one is carried across: the secret is
     * rewritten under its owner's id and the old slot removed.
     */
    private suspend fun migrateSingleAccount() {
        if (settings.getString(KEY_ROSTER) != null) return
        val previous = PubKey.parseOrNull(settings.getString(KEY_PUBKEY)) ?: return

        val legacyKey = secrets.readLegacySecKeyHex()
        val signerPackage = settings.getString(KEY_SIGNER_PACKAGE)?.takeIf { it.isNotBlank() }

        // The secret is what says who this is: a stored pubkey that disagrees
        // with it is the stale half of the pair.
        val owner = legacyKey?.let { keyTool.pubKeyOf(it) } ?: previous
        legacyKey?.let { secrets.writeSecKeyHex(owner.hex, it) }
        secrets.clearLegacy()

        val kind =
            when {
                legacyKey != null -> CredentialKind.LocalKey
                signerPackage != null -> CredentialKind.ExternalSigner
                else -> CredentialKind.WatchOnly
            }

        settings.putString(
            KEY_ROSTER,
            encodeRoster(listOf(AccountSummary(owner, bech32.encodeNpub(owner), kind, signerPackage))),
        )
        settings.putString(KEY_ACTIVE, owner.hex)
        settings.remove(KEY_PUBKEY)
        settings.remove(KEY_SIGNER_PACKAGE)
    }

    private fun Credential.kind(): CredentialKind =
        when (this) {
            is Credential.LocalKey -> CredentialKind.LocalKey
            is Credential.ExternalSigner -> CredentialKind.ExternalSigner
            Credential.WatchOnly -> CredentialKind.WatchOnly
        }

    /**
     * One line per account: pubkey, how it signs, and the signer's package.
     *
     * Tab-separated like the relay directory's own file, and read the same way:
     * a line that does not parse is skipped rather than failing the load, so a
     * field added by a later build cannot lock somebody out of every account
     * they have.
     */
    private fun encodeRoster(accounts: List<AccountSummary>): String =
        accounts.joinToString("\n") { "${it.pubKey.hex}\t${it.kind.name}\t${it.signerPackage.orEmpty()}" }

    private fun decodeRoster(text: String?): List<AccountSummary> =
        text
            ?.lineSequence()
            .orEmpty()
            .mapNotNull { line ->
                val fields = line.split("\t")
                val pubKey = PubKey.parseOrNull(fields.getOrNull(0)?.trim()) ?: return@mapNotNull null
                val kind = CredentialKind.entries.firstOrNull { it.name == fields.getOrNull(1) } ?: CredentialKind.WatchOnly
                AccountSummary(
                    pubKey = pubKey,
                    npub = bech32.encodeNpub(pubKey),
                    kind = kind,
                    signerPackage = fields.getOrNull(2)?.takeIf { it.isNotBlank() },
                )
            }.distinctBy { it.pubKey }
            .toList()

    private companion object {
        /** Every account signed in, one per line. */
        const val KEY_ROSTER = "account.list.v1"

        /** Which of them is active. */
        const val KEY_ACTIVE = "account.active"

        /** What builds before multi-account wrote. Read once, by the migration. */
        const val KEY_PUBKEY = "account.pubkey"
        const val KEY_SIGNER_PACKAGE = "account.signer.package"
    }
}
