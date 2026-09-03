package app.wayfarer.core.store

import app.wayfarer.core.model.MediaDirectorySnapshot
import app.wayfarer.core.model.MediaGrant
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.MediaReason
import app.wayfarer.core.model.MediaSource
import app.wayfarer.core.model.PendingMediaHost
import app.wayfarer.core.relay.MediaDirectoryStore

/**
 * Serializes the media directory as line-oriented text.
 *
 * The same hand-written format as [RelayDirectoryCodec], for the same reason:
 * this is the core module, which carries no serialization dependency, and the
 * shape is three flat record types. Fields are tab-separated and no field can
 * contain a tab — a host is validated to exclude whitespace, and the rest are
 * enum names and numbers — so no escaping is needed. Unparseable lines are
 * skipped rather than failing the load, so a field added by a later build cannot
 * lock a user out of their own settings.
 *
 *   M<TAB>host<TAB>load
 *   Q<TAB>host<TAB>firstSeen<TAB>lastSeen<TAB>SOURCE:detail<TAB>SOURCE:detail…
 *   X<TAB>host
 *
 * Deliberately different record letters from the relay codec's `G`/`P`/`D`/`F`,
 * and a different storage key. The two directories are separate promises: a
 * media file that is truncated, hand-edited or written by a build that knew a
 * different format must not be able to disturb which *relays* this app may open
 * a socket to. Distinct letters mean that if the two ever were confused for each
 * other, every line would fail to parse and be skipped, rather than a `G` from
 * one file quietly becoming a grant in the other.
 *
 * When a second permission arrives — uploading, if NIP-96 or Blossom is ever
 * supported — it goes in a new record type rather than a fourth column on `M`,
 * because `M` parses its boolean with `?: continue` and a column older builds do
 * not write would drop every grant on downgrade.
 */
class MediaDirectoryCodec {
    fun encode(snapshot: MediaDirectorySnapshot): String =
        buildString {
            for (grant in snapshot.grants.values.sortedBy { it.host }) {
                append("M\t").append(grant.host.host).append('\t').append(grant.load).append('\n')
            }
            for (pending in snapshot.pending.values.sortedBy { it.host }) {
                append("Q\t").append(pending.host.host).append('\t').append(pending.firstSeenAt).append('\t').append(pending.lastSeenAt)
                for (reason in pending.reasons) {
                    append('\t').append(reason.source.name).append(':').append(reason.detail?.replace('\t', ' ').orEmpty())
                }
                append('\n')
            }
            for (denied in snapshot.denied.sorted()) {
                append("X\t").append(denied.host).append('\n')
            }
        }

    fun decode(text: String?): MediaDirectorySnapshot {
        if (text.isNullOrBlank()) return MediaDirectorySnapshot()

        val grants = mutableMapOf<MediaHost, MediaGrant>()
        val pending = mutableMapOf<MediaHost, PendingMediaHost>()
        val denied = mutableSetOf<MediaHost>()

        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            val host = MediaHost.parseOrNull(parts.getOrNull(1)) ?: continue
            when (parts.getOrNull(0)) {
                "M" -> {
                    val load = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: continue
                    if (load) grants[host] = MediaGrant(host, load = true)
                }
                "Q" -> {
                    val first = parts.getOrNull(2)?.toLongOrNull() ?: continue
                    val last = parts.getOrNull(3)?.toLongOrNull() ?: first
                    val reasons = parts.drop(4).mapNotNullTo(mutableSetOf(), ::decodeReason)
                    pending[host] = PendingMediaHost(host, reasons, first, last)
                }
                "X" -> denied += host
            }
        }

        // A grant always wins over a stale queued or denied record for the same
        // host, so a half-written file can never leave one both approved and
        // waiting to be approved.
        return MediaDirectorySnapshot(
            grants = grants,
            pending = pending - grants.keys,
            denied = denied - grants.keys,
        )
    }

    private fun decodeReason(field: String): MediaReason? {
        val separator = field.indexOf(':')
        if (separator < 0) return null
        val source = MediaSource.entries.firstOrNull { it.name == field.substring(0, separator) } ?: return null
        return MediaReason(source, field.substring(separator + 1).takeIf { it.isNotEmpty() })
    }
}

/** [MediaDirectoryStore] on top of a [KeyValueStore] and [MediaDirectoryCodec]. */
class PersistedMediaDirectoryStore(
    private val store: KeyValueStore,
    private val codec: MediaDirectoryCodec,
    private val key: String = "media.directory.v1",
) : MediaDirectoryStore {
    override suspend fun load(): MediaDirectorySnapshot = codec.decode(store.getString(key))

    override suspend fun save(snapshot: MediaDirectorySnapshot) = store.putString(key, codec.encode(snapshot))
}
