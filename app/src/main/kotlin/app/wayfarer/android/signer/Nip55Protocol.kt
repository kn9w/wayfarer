package app.wayfarer.android.signer

/**
 * The NIP-55 wire format, with no Android types in sight.
 *
 * Everything about talking to an Android signer app that is *decidable* — what
 * the `nostrsigner:` URI looks like, which extras a method needs, how to read a
 * reply — lives here so it can be unit-tested without a device. `Nip55Bridge`
 * holds only the Intent launch and the activity-result round trip.
 */
object Nip55Protocol {
    const val SCHEME = "nostrsigner"

    /** Extras the signer reads. */
    const val EXTRA_TYPE = "type"
    const val EXTRA_ID = "id"
    const val EXTRA_CURRENT_USER = "current_user"
    const val EXTRA_PERMISSIONS = "permissions"

    /** Extras the signer writes back. */
    const val EXTRA_RESULT = "result"
    const val EXTRA_EVENT = "event"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_REJECTED = "rejected"

    /** One request, ready for the caller to turn into an Intent. */
    data class Request(
        /** Goes after `nostrsigner:` in the URI data. Empty for `get_public_key`. */
        val payload: String,
        /** Intent extras, as plain strings. */
        val extras: Map<String, String>,
        /**
         * The signer package to address, or null for `get_public_key` — that one
         * is broadcast so Android can offer whichever signer apps are installed.
         */
        val packageName: String?,
    ) {
        val uri: String get() = "$SCHEME:$payload"
    }

    /**
     * Asks the signer who the user is, and which app answered.
     *
     * [permissions] is a pre-authorisation hint: the signer may use it to let the
     * user grant these methods once rather than approving each request. It is
     * advisory, so a signer that ignores it simply prompts every time.
     */
    fun getPublicKey(permissions: List<Permission> = DEFAULT_PERMISSIONS): Request =
        Request(
            payload = "",
            extras =
                buildMap {
                    put(EXTRA_TYPE, "get_public_key")
                    if (permissions.isNotEmpty()) put(EXTRA_PERMISSIONS, encodePermissions(permissions))
                },
            packageName = null,
        )

    /**
     * Asks the signer to sign [eventJson].
     *
     * [id] is echoed back untouched; using the caller's own correlation id is
     * what lets a reply be matched to its request.
     */
    fun signEvent(
        eventJson: String,
        currentUserHex: String,
        signerPackage: String,
        id: String,
    ): Request =
        Request(
            payload = eventJson,
            extras =
                mapOf(
                    EXTRA_TYPE to "sign_event",
                    EXTRA_ID to id,
                    EXTRA_CURRENT_USER to currentUserHex,
                ),
            packageName = signerPackage,
        )

    /** A method the client would like pre-authorised. */
    data class Permission(
        val type: String,
        /** Only meaningful for `sign_event`. */
        val kind: Int? = null,
    )

    /**
     * The kinds this app signs. Listing them lets a signer offer "remember this"
     * once instead of prompting on every note.
     *
     * A kind missing here still signs — the signer just asks every time — so the
     * cost of an omission is silent and only felt by users of an external signer.
     * Each entry names the action it covers so a new publishing path has a
     * visible place to register itself:
     *
     *  - 0     editing your profile       `ProfileRepository.publish`
     *  - 1     posting a note             `FeedRepository.post`
     *  - 3     following, unfollowing     `ContactRepository.follow` / `unfollow`
     *  - 1111  replying to anything       `ThreadRepository.comment`
     *  - 10002 publishing your relay list `RelayListRepository.publishOwn`
     *  - 30023 publishing an article      `ArticleRepository.publish`
     */
    val DEFAULT_PERMISSIONS =
        listOf(
            Permission("sign_event", kind = 0),
            Permission("sign_event", kind = 1),
            Permission("sign_event", kind = 3),
            Permission("sign_event", kind = 1111),
            Permission("sign_event", kind = 10002),
            Permission("sign_event", kind = 30023),
        )

    /**
     * Hand-rolled rather than pulling in a JSON library: the shape is a fixed
     * array of one- or two-field objects with no user-controlled strings in it
     * (types are literals, kinds are integers), so there is nothing to escape.
     */
    fun encodePermissions(permissions: List<Permission>): String =
        permissions.joinToString(separator = ",", prefix = "[", postfix = "]") { permission ->
            if (permission.kind == null) {
                """{"type":"${permission.type}"}"""
            } else {
                """{"type":"${permission.type}","kind":${permission.kind}}"""
            }
        }

    /** What came back from the signer. */
    sealed interface Reply {
        data class Ok(
            /** The method result: a pubkey, a signature, a ciphertext. */
            val result: String?,
            /** The signed event JSON, for `sign_event`. */
            val event: String?,
            /** The signer's package name, for `get_public_key`. */
            val packageName: String?,
        ) : Reply

        /** The user saw the request and said no. */
        data object Rejected : Reply

        /** The signer failed, crashed, or was dismissed without answering. */
        data class Failed(
            val message: String,
        ) : Reply
    }

    /**
     * Reads a reply.
     *
     * The distinction that matters, and that the spec is explicit about: a
     * result code other than OK means the *signer* failed, while a user
     * rejection arrives as a successful result carrying `rejected = true`.
     * Collapsing the two would either report a crash as "you declined" or retry
     * forever against a user who already said no.
     */
    fun parseReply(
        resultOk: Boolean,
        extras: Map<String, String?>?,
    ): Reply {
        if (!resultOk) return Reply.Failed("The signer app did not complete the request")
        if (extras == null) return Reply.Failed("The signer app returned no data")
        if (extras[EXTRA_REJECTED]?.lowercase() == "true") return Reply.Rejected

        val result = extras[EXTRA_RESULT]
        val event = extras[EXTRA_EVENT]
        if (result == null && event == null) return Reply.Failed("The signer app returned an empty result")

        return Reply.Ok(result = result, event = event, packageName = extras[EXTRA_PACKAGE])
    }
}
