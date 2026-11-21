package app.wayfarer.core.model

/**
 * A 32-byte x-only nostr public key, lowercase hex.
 *
 * Always construct through [parseOrNull] at a trust boundary (relay data, user
 * input, disk). The constructor asserts the invariant so nothing downstream has
 * to re-check it.
 */
@JvmInline
value class PubKey(
    val hex: String,
) {
    init {
        require(isLowercaseHex(hex, 64)) { "not a 64-char lowercase hex pubkey" }
    }

    /** First and last few characters, for logs and dense UI. */
    fun abbreviated(): String = hex.take(8) + "…" + hex.takeLast(4)

    companion object {
        fun parseOrNull(hex: String?): PubKey? {
            val normalized = hex?.trim()?.lowercase() ?: return null
            return if (isLowercaseHex(normalized, 64)) PubKey(normalized) else null
        }
    }
}

/**
 * A 32-byte nostr secret key, lowercase hex.
 *
 * Deliberately not a `data class` and with [toString] overridden: a secret key
 * must never reach a log line or a crash report by accident.
 */
@JvmInline
value class SecKey(
    val hex: String,
) {
    init {
        require(isLowercaseHex(hex, 64)) { "not a 64-char lowercase hex secret key" }
    }

    override fun toString(): String = "SecKey(<redacted>)"

    companion object {
        fun parseOrNull(hex: String?): SecKey? {
            val normalized = hex?.trim()?.lowercase() ?: return null
            return if (isLowercaseHex(normalized, 64)) SecKey(normalized) else null
        }
    }
}

/** A 32-byte nostr event id, lowercase hex. */
@JvmInline
value class EventId(
    val hex: String,
) {
    init {
        require(isLowercaseHex(hex, 64)) { "not a 64-char lowercase hex event id" }
    }

    companion object {
        fun parseOrNull(hex: String?): EventId? {
            val normalized = hex?.trim()?.lowercase() ?: return null
            return if (isLowercaseHex(normalized, 64)) EventId(normalized) else null
        }
    }
}

private fun isLowercaseHex(
    value: String,
    length: Int,
): Boolean {
    if (value.length != length) return false
    return value.all { it in '0'..'9' || it in 'a'..'f' }
}
