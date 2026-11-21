package app.wayfarer.core.model

/**
 * NIP-01 kind 0 content, reduced to the fields this app renders or edits.
 *
 * Parsing the JSON is delegated to the nostr SPI ([app.wayfarer.core.nostr.NostrCodec])
 * so the core carries no JSON library of its own; unknown fields are preserved
 * there and merged back on publish so editing a profile in Wayfarer never drops
 * a field some other client set.
 */
data class Profile(
    val pubKey: PubKey,
    val name: String? = null,
    val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val website: String? = null,
    val nip05: String? = null,
    /**
     * A lightning address (NIP-57).
     *
     * Shown on the profile beside the NIP-A3 payment targets, because to a
     * reader they are one fact — where this person takes money — and the split
     * is only that this one predates the general answer. It stays modelled
     * rather than being read off the kind 10133 alone: plenty of profiles carry
     * nothing but this, and an edit here republishes the whole kind 0, so
     * dropping the field would delete somebody's address the first time they
     * corrected a typo in their bio.
     */
    val lud16: String? = null,
    /** createdAt of the kind 0 this came from; used to keep the newest. */
    val updatedAt: Long = 0,
) {
    /**
     * What this person calls themselves, or null when nothing is known.
     *
     * Null rather than a hex fallback. The fallback used to be
     * [PubKey.abbreviated], which is hex — so an author with no metadata was
     * shown as `a1b2c3d4…9f0e` on one screen and as an npub on the next. Making
     * the absence explicit forces each caller to choose, and the only correct
     * choice for a person is an npub.
     */
    fun displayNameOrNull(): String? =
        displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }

    companion object {
        fun empty(pubKey: PubKey) = Profile(pubKey)
    }
}

/** The editable subset, as the profile form submits it. */
data class ProfileDraft(
    val name: String,
    val displayName: String,
    val about: String,
    val picture: String,
    val banner: String,
    val website: String,
    val nip05: String,
    val lud16: String,
) {
    companion object {
        fun from(profile: Profile) =
            ProfileDraft(
                name = profile.name.orEmpty(),
                displayName = profile.displayName.orEmpty(),
                about = profile.about.orEmpty(),
                picture = profile.picture.orEmpty(),
                banner = profile.banner.orEmpty(),
                website = profile.website.orEmpty(),
                nip05 = profile.nip05.orEmpty(),
                lud16 = profile.lud16.orEmpty(),
            )
    }
}
