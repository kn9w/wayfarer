package app.wayfarer.core.repo

import app.wayfarer.core.model.PubKey
import app.wayfarer.core.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * People followed on this phone only.
 *
 * The counterpart to the relay permission list, and for the same reason: a
 * kind 3 follow is a public statement, signed and broadcast, and there are
 * people a reader wants to read without announcing it. Nothing here is ever
 * published: it produces no event, and no other client can enumerate this list.
 *
 * What it does not do is hide the names from the relays this app talks to.
 * Reading somebody's posts means asking a relay for them by pubkey, so a relay
 * serving the feed sees every author on this list in the `authors` filter, the
 * same as it sees the published ones. The privacy this buys is from other
 * *users*, not from the relay operator — see [FollowBook].
 *
 * Kept per account. The list is keyed by the pubkey that owns it, so signing in
 * as somebody else shows their list rather than inheriting the last one, and
 * signing out leaves nothing on screen belonging to an account that is gone.
 *
 * One key per account rather than one record per follow: this is a flat set of
 * hex pubkeys, so a line-oriented blob costs nothing to parse. Unparseable lines
 * are skipped rather than failing the load, which is the rule
 * [app.wayfarer.core.store.RelayDirectoryCodec] documents at more length — a
 * field added by a later build must not lock a user out of their own list.
 */
class LocalFollowStore(
    private val settings: KeyValueStore,
) {
    private val state = MutableStateFlow<Set<PubKey>>(emptySet())

    private var owner: PubKey? = null

    val follows: StateFlow<Set<PubKey>> = state.asStateFlow()

    /** Reads the list belonging to [account]. Replaces whatever was loaded before. */
    suspend fun load(account: PubKey) {
        owner = account
        state.value = decode(settings.getString(keyFor(account)))
    }

    suspend fun add(pubKey: PubKey) {
        val account = owner ?: return
        if (pubKey in state.value) return
        state.value = state.value + pubKey
        settings.putString(keyFor(account), encode(state.value))
    }

    suspend fun remove(pubKey: PubKey) {
        val account = owner ?: return
        if (pubKey !in state.value) return
        state.value = state.value - pubKey
        settings.putString(keyFor(account), encode(state.value))
    }

    /**
     * Forgets the loaded list without deleting it.
     *
     * Signing out should not destroy the list: signing back in is expected to
     * bring it back, exactly as the account's own relay grants do.
     */
    fun clear() {
        owner = null
        state.value = emptySet()
    }

    private fun keyFor(account: PubKey) = "$KEY_PREFIX${account.hex}"

    private fun encode(follows: Set<PubKey>) = follows.map { it.hex }.sorted().joinToString("\n")

    private fun decode(text: String?): Set<PubKey> =
        text
            ?.lineSequence()
            .orEmpty()
            .mapNotNull { PubKey.parseOrNull(it.trim()) }
            .toSet()

    private companion object {
        const val KEY_PREFIX = "follows.local."
    }
}
