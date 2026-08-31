package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.ProfileDraft
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayListEntry
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayInfo
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayType

/**
 * Reading and writing the four event kinds this app understands, via Quartz's
 * per-NIP event classes.
 *
 * Each method builds the Quartz event type for the kind, uses its accessors, and
 * hands back the app's own model. The kind-specific knowledge Quartz supplies
 * here — the exact JSON field names of kind 0, the `r`-tag grammar of NIP-65,
 * the `p`-tag validation of NIP-02 — is the substantive part; the rest is
 * mechanical translation.
 */
class QuartzNostrCodec : NostrCodec {
    override fun readProfile(event: NostrEvent): Profile? {
        val metadata = event.asMetadataEvent().contactMetaData() ?: return null
        return Profile(
            pubKey = event.pubKey,
            name = metadata.name?.takeIf { it.isNotBlank() },
            displayName = metadata.displayName?.takeIf { it.isNotBlank() },
            about = metadata.about?.takeIf { it.isNotBlank() },
            picture = metadata.picture?.takeIf { it.isNotBlank() },
            banner = metadata.banner?.takeIf { it.isNotBlank() },
            website = metadata.website?.takeIf { it.isNotBlank() },
            nip05 = metadata.nip05?.takeIf { it.isNotBlank() },
            lud16 = metadata.lud16?.takeIf { it.isNotBlank() },
            updatedAt = event.createdAt,
        )
    }

    /**
     * Quartz's `updateFromPast` re-serializes the previous kind 0's JSON and
     * overwrites only the named fields, so a field Wayfarer has no UI for — say
     * `lud06`, or a client-specific extension — survives an edit here. An empty
     * string deletes a field; that is what the form submits for a cleared input.
     */
    override fun writeProfile(
        previous: NostrEvent?,
        draft: ProfileDraft,
        createdAt: Long,
    ): UnsignedEvent {
        val template =
            if (previous != null) {
                MetadataEvent.updateFromPast(
                    latest = previous.asMetadataEvent(),
                    name = draft.name,
                    displayName = draft.displayName,
                    picture = draft.picture,
                    banner = draft.banner,
                    website = draft.website,
                    about = draft.about,
                    nip05 = draft.nip05,
                    lnAddress = draft.lud16,
                    createdAt = createdAt,
                )
            } else {
                MetadataEvent.createNew(
                    name = draft.name,
                    displayName = draft.displayName,
                    picture = draft.picture,
                    banner = draft.banner,
                    website = draft.website,
                    about = draft.about,
                    nip05 = draft.nip05,
                    lnAddress = draft.lud16,
                    createdAt = createdAt,
                )
            }
        return template.toUnsigned()
    }

    override fun readRelayList(event: NostrEvent): List<RelayListEntry> =
        AdvertisedRelayListEvent(
            event.id.hex,
            event.pubKey.hex,
            event.createdAt,
            event.tags.toQuartzTags(),
            event.content,
            event.sig,
        ).relays()
            .map { info ->
                RelayListEntry(
                    url = RelayUrl(info.relayUrl.url),
                    read = info.type.isRead(),
                    write = info.type.isWrite(),
                )
            }

    override fun writeRelayList(
        entries: List<RelayListEntry>,
        createdAt: Long,
    ): UnsignedEvent {
        val tags =
            entries.map { entry ->
                val type =
                    when {
                        entry.read && entry.write -> AdvertisedRelayType.BOTH
                        entry.read -> AdvertisedRelayType.READ
                        else -> AdvertisedRelayType.WRITE
                    }
                AdvertisedRelayInfo.assemble(entry.url.toQuartz(), type).toList()
            }
        return UnsignedEvent(
            kind = AdvertisedRelayListEvent.KIND,
            content = "",
            tags = tags,
            createdAt = createdAt,
        )
    }

    override fun readFollows(event: NostrEvent): Set<PubKey> =
        ContactListEvent(
            event.id.hex,
            event.pubKey.hex,
            event.createdAt,
            event.tags.toQuartzTags(),
            event.content,
            event.sig,
        ).verifiedFollowKeySet()
            .mapNotNullTo(mutableSetOf(), PubKey::parseOrNull)

    /**
     * Recomputes the id from the serialized event and checks the schnorr
     * signature. Quartz's `verify()` does both; an event failing either is
     * dropped before it reaches the note store.
     */
    override fun verify(event: NostrEvent): Boolean =
        runCatching { QuartzEventMapping.toQuartz(event).verify() }.getOrDefault(false)

    private fun NostrEvent.asMetadataEvent() = MetadataEvent(id.hex, pubKey.hex, createdAt, tags.toQuartzTags(), content, sig)

    private fun List<List<String>>.toQuartzTags(): Array<Array<String>> = map { it.toTypedArray() }.toTypedArray()

    private fun EventTemplate<*>.toUnsigned() =
        UnsignedEvent(
            kind = kind,
            content = content,
            tags = tags.map { it.toList() },
            createdAt = createdAt,
        )
}
