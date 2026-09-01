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
    val lud16: String? = null,
    /** createdAt of the kind 0 this came from; used to keep the newest. */
    val updatedAt: Long = 0,
) {
    fun bestName(): String = displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: pubKey.abbreviated()

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
