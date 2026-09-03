package app.wayfarer.nostr.quartz

import app.wayfarer.core.model.Article
import app.wayfarer.core.model.ArticleDraft
import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.Profile
import app.wayfarer.core.model.ProfileDraft
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.UnsignedEvent
import app.wayfarer.core.nostr.NostrCodec
import app.wayfarer.core.nostr.RelayListEntry
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip23LongContent.LongTextNoteEvent
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

    override fun readArticle(event: NostrEvent): Article? {
        val article = event.asLongFormEvent()
        val title = article.title()?.takeIf { it.isNotBlank() } ?: return null
        val dTag = article.dTag().takeIf { it.isNotBlank() } ?: return null

        return Article(
            id = event.id,
            author = event.pubKey,
            dTag = dTag,
            title = title,
            summary = article.summary()?.takeIf { it.isNotBlank() },
            image = article.image()?.takeIf { it.isNotBlank() },
            // NIP-23 makes published_at optional; created_at is the sensible
            // stand-in, and it is what the UI sorts by either way.
            publishedAt = article.publishedAt() ?: event.createdAt,
            content = event.content,
            createdAt = event.createdAt,
            // Straight off the tags rather than through Quartz: NIP-23 says `t`
            // is the topic tag and that is the whole of the format, so there is
            // nothing here for a library to know that this does not.
            topics = event.tagValues("t").map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    /**
     * Builds a kind 30023.
     *
     * Two things survive an edit that the event builder alone would not carry.
     * `published_at` is NIP-23's record of when the article *first* appeared,
     * and stamping it with the moment of the edit — which is what passing
     * `createdAt` did — threw away the article's real age on every correction.
     * The `t` tags are the author's topics, which this app shows but offers no
     * way to change, and republishing an addressable event replaces the whole
     * of it: a field not offered is a field that must not be dropped.
     */
    override fun writeArticle(
        draft: ArticleDraft,
        createdAt: Long,
    ): UnsignedEvent {
        val template =
            LongTextNoteEvent
                .build(
                    description = draft.content,
                    title = draft.title,
                    summary = draft.summary.takeIf { it.isNotBlank() },
                    image = draft.image.takeIf { it.isNotBlank() },
                    publishedAt = draft.publishedAt.takeIf { it > 0 } ?: createdAt,
                    // Quartz would otherwise mint a random UUID d tag, which for
                    // an addressable event means every edit publishes a new
                    // article instead of replacing the old one.
                    dTag = draft.dTag,
                    createdAt = createdAt,
                ).toUnsigned()

        val carried = draft.topics.map { listOf("t", it) }.filterNot { it in template.tags }
        return if (carried.isEmpty()) template else template.copy(tags = template.tags + carried)
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
     * The unsigned event, as NIP-55 wants it.
     *
     * `id` and `sig` are sent empty: the signer recomputes the id from the
     * content itself — it has to, since it cannot trust a client-supplied one —
     * and the signature is the thing being asked for.
     */
    override fun encodeForSigning(
        unsigned: UnsignedEvent,
        authorPubKeyHex: String,
    ): String =
        Event(
            id = "",
            pubKey = authorPubKeyHex,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags.map { it.toTypedArray() }.toTypedArray(),
            content = unsigned.content,
            sig = "",
        ).toJson()

    override fun decodeEvent(json: String): NostrEvent? = Event.fromJsonOrNull(json)?.let(QuartzEventMapping::toCore)

    /**
     * The signed event, as JSON. Same construction as [encodeForSigning], with
     * the id and signature this one actually has.
     */
    override fun encodeEvent(event: NostrEvent): String =
        Event(
            id = event.id.hex,
            pubKey = event.pubKey.hex,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags.map { it.toTypedArray() }.toTypedArray(),
            content = event.content,
            sig = event.sig,
        ).toJson()

    /**
     * Recomputes the id from the serialized event and checks the schnorr
     * signature. Quartz's `verify()` does both; an event failing either is
     * dropped before it reaches the note store.
     */
    override fun verify(event: NostrEvent): Boolean =
        runCatching { QuartzEventMapping.toQuartz(event).verify() }.getOrDefault(false)

    private fun NostrEvent.asMetadataEvent() = MetadataEvent(id.hex, pubKey.hex, createdAt, tags.toQuartzTags(), content, sig)

    private fun NostrEvent.asLongFormEvent() = LongTextNoteEvent(id.hex, pubKey.hex, createdAt, tags.toQuartzTags(), content, sig)

    private fun List<List<String>>.toQuartzTags(): Array<Array<String>> = map { it.toTypedArray() }.toTypedArray()

    private fun EventTemplate<*>.toUnsigned() =
        UnsignedEvent(
            kind = kind,
            content = content,
            tags = tags.map { it.toList() },
            createdAt = createdAt,
        )
}
