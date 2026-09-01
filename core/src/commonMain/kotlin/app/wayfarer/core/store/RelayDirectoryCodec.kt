package app.wayfarer.core.store

import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.PendingRelay
import app.wayfarer.core.model.RelayDirectorySnapshot
import app.wayfarer.core.model.RelayGrant
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.RelayUrlNormalizer
import app.wayfarer.core.relay.RelayDirectoryStore

/**
 * Serializes the relay directory as line-oriented text.
 *
 * A hand-written format rather than a JSON library: this is the core module,
 * which carries no serialization dependency, and the shape is three flat record
 * types. Fields are tab-separated and every field is either an enum name, a
 * number, or a URL, none of which can contain a tab — so no escaping is needed.
 * Unparseable lines are skipped rather than failing the load, so a forward-
 * compatible field added later cannot lock a user out of their own settings.
 *
 *   G<TAB>url<TAB>read<TAB>write
 *   P<TAB>url<TAB>firstSeen<TAB>lastSeen<TAB>SOURCE:detail<TAB>SOURCE:detail…
 *   D<TAB>url
 *   F<TAB>url
 *
 * `F` is a record type rather than a fifth column on `G`, and that is load
 * bearing. `G` parses its booleans with `?: continue`, so a column older builds
 * did not write would drop every grant on downgrade; `P` is open-ended from
 * index 4, so nothing can be appended there at all. A new line type is the only
 * shape that leaves existing files byte-for-byte readable in both directions.
 */
class RelayDirectoryCodec(
    private val normalizer: RelayUrlNormalizer,
) {
    fun encode(snapshot: RelayDirectorySnapshot): String =
        buildString {
            for (grant in snapshot.grants.values.sortedBy { it.url }) {
                append("G\t").append(grant.url.url).append('\t').append(grant.read).append('\t').append(grant.write).append('\n')
            }
            for (pending in snapshot.pending.values.sortedBy { it.url }) {
                append("P\t").append(pending.url.url).append('\t').append(pending.firstSeenAt).append('\t').append(pending.lastSeenAt)
                for (reason in pending.reasons) {
                    append('\t').append(reason.source.name).append(':').append(reason.detail?.replace('\t', ' ').orEmpty())
                }
                append('\n')
            }
            for (denied in snapshot.denied.sorted()) {
                append("D\t").append(denied.url).append('\n')
            }
            for (favourite in snapshot.favourites.sorted()) {
                append("F\t").append(favourite.url).append('\n')
            }
        }

    fun decode(text: String?): RelayDirectorySnapshot {
        if (text.isNullOrBlank()) return RelayDirectorySnapshot()

        val grants = mutableMapOf<RelayUrl, RelayGrant>()
        val pending = mutableMapOf<RelayUrl, PendingRelay>()
        val denied = mutableSetOf<RelayUrl>()
        val favourites = mutableSetOf<RelayUrl>()

        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            val url = parts.getOrNull(1)?.let(normalizer::normalize) ?: continue
            when (parts.getOrNull(0)) {
                "G" -> {
                    val read = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: continue
                    val write = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: continue
                    if (read || write) grants[url] = RelayGrant(url, read, write)
                }
                "P" -> {
                    val first = parts.getOrNull(2)?.toLongOrNull() ?: continue
                    val last = parts.getOrNull(3)?.toLongOrNull() ?: first
                    val reasons = parts.drop(4).mapNotNullTo(mutableSetOf(), ::decodeReason)
                    pending[url] = PendingRelay(url, reasons, first, last)
                }
                "D" -> denied += url
                "F" -> favourites += url
            }
        }

        // A grant always wins over a stale pending or denied record for the same
        // relay, so a half-written file can never leave a relay both approved and
        // queued for approval.
        return RelayDirectorySnapshot(
            grants = grants,
            pending = pending - grants.keys,
            denied = denied - grants.keys,
            favourites = favourites,
        )
    }

    private fun decodeReason(field: String): DiscoveryReason? {
        val separator = field.indexOf(':')
        if (separator < 0) return null
        val source = DiscoverySource.entries.firstOrNull { it.name == field.substring(0, separator) } ?: return null
        return DiscoveryReason(source, field.substring(separator + 1).takeIf { it.isNotEmpty() })
    }
}

/** [RelayDirectoryStore] on top of a [KeyValueStore] and [RelayDirectoryCodec]. */
class PersistedRelayDirectoryStore(
    private val store: KeyValueStore,
    private val codec: RelayDirectoryCodec,
    private val key: String = "relay.directory.v1",
) : RelayDirectoryStore {
    override suspend fun load(): RelayDirectorySnapshot = codec.decode(store.getString(key))

    override suspend fun save(snapshot: RelayDirectorySnapshot) = store.putString(key, codec.encode(snapshot))
}
