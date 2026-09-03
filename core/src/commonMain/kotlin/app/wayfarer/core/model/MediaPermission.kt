package app.wayfarer.core.model

/**
 * A host that serves pictures, in the single normalized form the permission map
 * uses as an identity key.
 *
 * A host rather than a full URL, because the decision a user is making is about
 * a server and not about a file: one host serves every avatar its users upload,
 * and being asked about each picture separately would be unanswerable. A host
 * rather than a scheme-and-host origin, because plain `http` is refused outright
 * rather than made approvable — there is nothing to decide about a picture that
 * would arrive in clear text.
 *
 * Extracting a host *from* a URL needs real URL parsing (userinfo, ports, IDN,
 * IPv6 literals), which is a platform job and deliberately does not happen here;
 * see `MediaUrls` in the app module. By the time a string is wrapped here it is
 * already a bare host, and two [MediaHost]s are equal exactly when they name the
 * same server — which is what makes the permission map trustworthy.
 */
@JvmInline
value class MediaHost(
    val host: String,
) : Comparable<MediaHost> {
    /** Hosts are already stored in the form worth showing. */
    fun display(): String = host

    override fun compareTo(other: MediaHost): Int = host.compareTo(other.host)

    companion object {
        /**
         * Null when [raw] is not a bare host.
         *
         * Strict on purpose, and the strictness is safe in the direction that
         * matters: a host this refuses is a host that never gets a grant, and a
         * host with no grant is never contacted. Rejecting something loadable
         * costs a picture; accepting something malformed would let a grant for
         * one server authorise a request to another.
         */
        fun parseOrNull(raw: String?): MediaHost? {
            val host = raw?.trim()?.lowercase()?.removeSuffix(".") ?: return null
            if (host.isEmpty() || host.length > 253) return null
            // No scheme, no path, no credentials, no port, no whitespace: this
            // takes a host and nothing else.
            if (host.any { it.isWhitespace() || it in "/\\@:?#[]" }) return null
            if (host.startsWith(".") || host.contains("..")) return null
            if (!host.all { it.isDigit() || it in 'a'..'z' || it == '-' || it == '.' }) return null
            return MediaHost(host)
        }
    }
}

/**
 * What the user has allowed for one media host.
 *
 * One flag, not the read/write pair [RelayGrant] carries. A relay genuinely has
 * two directions because NIP-65 marks them separately, but there is exactly one
 * thing this app does with a media host: ask it for a picture. Nothing uploads.
 * A second flag now would be a shape with no meaning behind it; when uploading
 * arrives, the codec's record types make room for one without rewriting any file
 * already on disk.
 *
 * A host with [load] false is *not* approved, and no request may be made to it.
 * As with relays, the absence of a grant is the denial.
 */
data class MediaGrant(
    val host: MediaHost,
    val load: Boolean,
) {
    val isApproved: Boolean get() = load

    companion object {
        fun loading(host: MediaHost) = MediaGrant(host, load = true)
    }
}

/** Why the app wanted a picture from a host it has no grant for. */
enum class MediaSource {
    /** A profile picture — the overwhelmingly common case. */
    AVATAR,

    /** The wide image across the top of a profile. */
    BANNER,

    /** A NIP-23 article's header image. */
    ARTICLE_IMAGE,

    /** Typed in by the user on the media screen. */
    USER_ENTERED,
}

/** One reason a host ended up in the queue, with human-readable context. */
data class MediaReason(
    val source: MediaSource,
    /** e.g. "avatar of npub1abc…" — shown verbatim on the media screen. */
    val detail: String? = null,
)

/**
 * A host the app has been asked for a picture from but has no grant for.
 *
 * Nothing has been requested from it and it does not know this app exists; it
 * sits here until the user decides.
 */
data class PendingMediaHost(
    val host: MediaHost,
    val reasons: Set<MediaReason>,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
) {
    fun merge(
        newReasons: Set<MediaReason>,
        now: Long,
    ) = copy(reasons = reasons + newReasons, lastSeenAt = now)
}

/** The full persisted state of the media permission system. */
data class MediaDirectorySnapshot(
    val grants: Map<MediaHost, MediaGrant> = emptyMap(),
    val pending: Map<MediaHost, PendingMediaHost> = emptyMap(),
    /** Hosts the user explicitly rejected. Kept so they stop reappearing. */
    val denied: Set<MediaHost> = emptySet(),
)
