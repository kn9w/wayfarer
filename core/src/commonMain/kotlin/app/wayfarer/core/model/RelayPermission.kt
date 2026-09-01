package app.wayfarer.core.model

/**
 * What the user has allowed for one relay. Read and write are independent, to
 * match NIP-65's own read/write markers: you may want to read from a relay you
 * would never publish to.
 *
 * A relay with neither flag set is *not* approved and no socket may be opened
 * to it. There is no third "approved but idle" state on purpose — the absence
 * of a grant is the denial.
 */
data class RelayGrant(
    val url: RelayUrl,
    val read: Boolean,
    val write: Boolean,
) {
    val isApproved: Boolean get() = read || write

    companion object {
        fun readOnly(url: RelayUrl) = RelayGrant(url, read = true, write = false)

        fun readWrite(url: RelayUrl) = RelayGrant(url, read = true, write = true)
    }
}

/** Why the app wanted to talk to a relay it has no grant for. */
enum class DiscoverySource {
    /** Shipped with the app as a starting suggestion. Never auto-approved. */
    BOOTSTRAP,

    /** Typed in by the user in settings. */
    USER_ENTERED,

    /** Listed in the signed-in account's own kind 10002. */
    OWN_RELAY_LIST,

    /** Listed in some other author's kind 10002 — outbox routing wants it. */
    AUTHOR_RELAY_LIST,

    /** A relay hint carried on an event tag or a NIP-19 entity. */
    EVENT_HINT,

    /** Listed in the account's kind 3 contact list (legacy relay field). */
    CONTACT_LIST,
}

/** One reason a relay ended up in the pending queue, with human-readable context. */
data class DiscoveryReason(
    val source: DiscoverySource,
    /** e.g. "write relay of npub1abc…" — shown verbatim in settings. */
    val detail: String? = null,
)

/**
 * A relay the app has been asked to use but has no grant for. Nothing has been
 * sent to it and no socket has been opened; it sits here until the user decides.
 */
data class PendingRelay(
    val url: RelayUrl,
    val reasons: Set<DiscoveryReason>,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
) {
    fun merge(
        newReasons: Set<DiscoveryReason>,
        now: Long,
    ) = copy(reasons = reasons + newReasons, lastSeenAt = now)
}

/** The full persisted state of the relay permission system. */
data class RelayDirectorySnapshot(
    val grants: Map<RelayUrl, RelayGrant> = emptyMap(),
    val pending: Map<RelayUrl, PendingRelay> = emptyMap(),
    /** Relays the user explicitly rejected. Kept so they stop reappearing as pending. */
    val denied: Set<RelayUrl> = emptySet(),
    /**
     * Relays the user starred, to be offered first when picking one to read.
     *
     * Deliberately its own set rather than a flag on [RelayGrant]: a grant is
     * *deleted* when a relay is revoked, so a star kept there would vanish with
     * it and reappear wrong on re-approval. A star is a lasting preference about
     * a relay, not part of what that relay is currently allowed to do.
     */
    val favourites: Set<RelayUrl> = emptySet(),
)
