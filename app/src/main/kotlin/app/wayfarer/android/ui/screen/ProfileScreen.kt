package app.wayfarer.android.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.stickyHeader
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.Avatar
import app.wayfarer.android.ui.BannerImage
import app.wayfarer.android.ui.IdentityChip
import app.wayfarer.android.ui.NpubQrCard
import app.wayfarer.android.ui.ScreenHeader
import app.wayfarer.android.ui.icons.WayfarerIcons
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.RelayApproval
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.android.viewmodel.shortenNpub
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.nostr.RelayListEntry

/** How far the avatar hangs below the banner. Half its size, so it straddles the edge. */
private val AvatarSize = 88.dp
private val BannerHeight = 132.dp

/**
 * One person: who they are, and what they have written.
 *
 * Structured as a header and a set of tabs rather than as one long column of
 * everything. The old shape put a person's name, bio, key, relay list, articles,
 * notes and a global search box in a single scroll, so a prolific author's
 * articles pushed their notes below the fold and the relay card sat between the
 * reader and the posts they came for.
 *
 * What is *not* here is as deliberate: there is no follower count and no
 * following count, because this app cannot know either. `ContactRepository` is a
 * single-slot store for the signed-in account, and nothing queries the network
 * for who follows whom. A number would have to be invented, so instead the facts
 * row says only what is true, and says "found" where the count is really "what
 * arrived from the relays you allow".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    controller: AppController,
    pubKey: PubKey,
) {
    val viewed by controller.viewedProfile.collectAsStateWithLifecycle()
    val account by controller.account.collectAsStateWithLifecycle()
    val articles by controller.articles.collectAsStateWithLifecycle()
    val offerRelayList by controller.shouldOfferRelayListPublish.collectAsStateWithLifecycle()
    val relayLists by controller.relayLists.collectAsStateWithLifecycle()
    val isMe = account?.pubKey == pubKey

    val authorArticles = articles.filter { it.author == pubKey }
    // Only this person's. viewedProfile is one slot, so between tapping a name
    // and the load starting it still holds whoever was on screen before.
    val mine = viewed?.takeIf { it.pubKey == pubKey }
    val notes = mine?.notes.orEmpty()
    val relayEntries = relayLists[pubKey]?.entries.orEmpty()

    val tabs = remember(authorArticles.isEmpty()) { ProfileTab.shownFor(hasArticles = authorArticles.isNotEmpty()) }
    var tab by remember(pubKey) { mutableStateOf(ProfileTab.Notes) }
    val selected = if (tab in tabs) tab else ProfileTab.Notes

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ProfileHeader(
                pubKey = pubKey,
                controller = controller,
                isMe = isMe,
                notesFound = notes.size,
                articleCount = authorArticles.size,
                relayEntries = relayEntries,
            )
        }

        // Both of these are calls to action rather than content, so they stay
        // above the tabs where they cannot be scrolled past by choosing a tab.
        if (isMe && offerRelayList) {
            item { RelayListPrompt(onPublish = controller::openRelayList) }
        }

        if (mine?.unreachable == true) {
            item {
                Text(
                    "This person publishes only to relays you have not approved, so nothing of theirs can be fetched. " +
                        "Their relays are waiting in the Relays tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = PostHorizontalPadding, vertical = 8.dp),
                )
            }
        }

        stickyHeader {
            PrimaryTabRow(
                selectedTabIndex = tabs.indexOf(selected),
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                for (option in tabs) {
                    Tab(
                        selected = option == selected,
                        onClick = { tab = option },
                        text = { Text(option.label(authorArticles.size, notes.size, relayEntries.size)) },
                    )
                }
            }
        }

        when (selected) {
            ProfileTab.Notes -> {
                if (mine?.loading == false && notes.isEmpty()) {
                    item { EmptyTabNote("No notes found.") }
                }
                items(notes, key = { it.id.hex }) { note ->
                    NoteRow(
                        note = note,
                        controller = controller,
                        onOpen = { controller.go(Screen.ReadNote(note.id)) },
                    )
                    PostDivider()
                }
            }

            ProfileTab.Articles -> {
                items(authorArticles, key = { it.address }) { article ->
                    ArticleRow(
                        article = article,
                        controller = controller,
                        onOpen = { controller.go(Screen.ReadArticle(article.address)) },
                    )
                    PostDivider()
                }
            }

            ProfileTab.Relays -> {
                item {
                    Box(Modifier.padding(horizontal = PostHorizontalPadding, vertical = 8.dp)) {
                        AdvertisedRelaysCard(
                            // From the reactive cache rather than a synchronous read:
                            // advertisedRelaysFor peeks at a map with no flow, so a
                            // relay list arriving after first composition never showed.
                            entries = relayEntries,
                            isMe = isMe,
                            controller = controller,
                            ownerName = controller.displayName(pubKey),
                            onManage = controller::openRelayList,
                            // It has a tab to itself now. Collapsed by default
                            // made sense when it sat between the header and the
                            // posts; here it would be an empty tab.
                            initiallyExpanded = true,
                        )
                    }
                }
            }
        }
    }
}

/** The tabs a profile can show. Articles disappears when there are none. */
private enum class ProfileTab {
    Notes,
    Articles,
    Relays,
    ;

    fun label(
        articles: Int,
        notes: Int,
        relays: Int,
    ): String =
        when (this) {
            // "found", not a total. What arrived is what the relays this phone
            // is allowed to reach happened to return, and calling that a count
            // of somebody's notes would be a number this app cannot stand behind.
            Notes -> if (notes == 0) "Notes" else "$notes found"
            Articles -> "Articles ($articles)"
            Relays -> if (relays == 0) "Relays" else "Relays ($relays)"
        }

    companion object {
        fun shownFor(hasArticles: Boolean): List<ProfileTab> =
            if (hasArticles) listOf(Notes, Articles, Relays) else listOf(Notes, Relays)
    }
}

@Composable
private fun EmptyTabNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = PostHorizontalPadding, vertical = 16.dp),
    )
}

// ---- the header ---------------------------------------------------------

/**
 * Who this is: a banner, a face, a name, and the handful of things a person
 * publishes about themselves.
 *
 * Every field is drawn only when it is there. A profile with nothing but a key
 * — which is what a stranger looks like before their kind 0 arrives, and what
 * some people leave forever — collapses to a mark, an npub chip and the follow
 * buttons, with no empty rows standing in for what is missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileHeader(
    pubKey: PubKey,
    controller: AppController,
    isMe: Boolean,
    notesFound: Int,
    articleCount: Int,
    relayEntries: List<RelayListEntry>,
) {
    // The reactive map rather than viewedProfile.profile: that snapshot is taken
    // when the screen opens, so a kind 0 arriving a second later — the normal
    // case for somebody you have just tapped through to — left the header
    // showing an npub and no bio until you navigated away and back.
    val profiles by controller.profiles.collectAsStateWithLifecycle()
    val profile = profiles[pubKey]
    val npub = controller.npubFor(pubKey)
    val clipboard = LocalClipboardManager.current
    var showQr by remember { mutableStateOf(false) }
    var bioExpanded by remember(pubKey) { mutableStateOf(false) }

    if (showQr) {
        ModalBottomSheet(onDismissRequest = { showQr = false }, sheetState = rememberModalBottomSheetState()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(controller.displayName(pubKey), style = MaterialTheme.typography.titleMedium)
                NpubQrCard(npub)
                Text(
                    npub,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "This is the public half of their key. Anyone can have it — it is how somebody is named on " +
                        "nostr, and there is no directory to look them up in instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Column {
        Box {
            BannerImage(
                pubKey = pubKey,
                controller = controller,
                height = BannerHeight,
                onOpenMedia = { host -> controller.openMediaHost(host, "banner on ${controller.displayName(pubKey)}") },
            )
            // Straddling the banner's lower edge, which is the shape every
            // profile on every platform has had for a decade — worth matching,
            // because it is what makes a header read as a header at a glance.
            Box(
                Modifier
                    .padding(start = PostHorizontalPadding, top = BannerHeight - AvatarSize / 2)
                    .size(AvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(3.dp),
            ) {
                Avatar(
                    pubKey = pubKey,
                    controller = controller,
                    size = AvatarSize - 6.dp,
                    markUndecided = true,
                )
            }
        }

        Column(
            Modifier.padding(horizontal = PostHorizontalPadding).padding(top = AvatarSize / 2 + 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NameBlock(pubKey, profile?.displayName, profile?.name, controller)

            profile?.about?.let { about ->
                Text(
                    about,
                    style = MaterialTheme.typography.bodyMedium,
                    // Four lines, then a tap. An unbounded bio pushed the follow
                    // buttons and every post off the first screen.
                    maxLines = if (bioExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { bioExpanded = !bioExpanded },
                )
            }

            profile?.nip05?.let { Nip05Line(it) }

            IdentityChips(
                npub = npub,
                lightning = profile?.lud16,
                website = profile?.website,
                onCopy = { clipboard.setText(AnnotatedString(it)) },
                onShowQr = { showQr = true },
            )

            FactsRow(
                controller = controller,
                pubKey = pubKey,
                notesFound = notesFound,
                articleCount = articleCount,
                relayEntries = relayEntries,
            )

            if (isMe) OwnProfileActions(controller) else FollowControls(pubKey, controller)
        }
    }
}

/**
 * The name, and the handle under it.
 *
 * Both, where a profile sets both. `displayNameOrNull` picks one and drops the
 * other, which is right for a byline and wrong here: `display_name` is what
 * somebody calls themselves and `name` is the short handle other clients show
 * with an `@`, and on the one screen about a person there is room for each.
 */
@Composable
private fun NameBlock(
    pubKey: PubKey,
    displayName: String?,
    name: String?,
    controller: AppController,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            controller.displayName(pubKey),
            style = MaterialTheme.typography.headlineSmall,
        )
        // Only when it adds something: repeating the title underneath itself in
        // grey is worse than showing nothing.
        val handle = name?.takeIf { !it.equals(displayName, ignoreCase = true) && !it.equals(controller.displayName(pubKey), true) }
        handle?.let {
            Text(
                "@$it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A NIP-05 identifier, shown as the claim it is.
 *
 * Nothing in this app verifies one, and that is not an oversight to paper over
 * with a tick: checking means an unprompted HTTPS request to whatever domain a
 * stranger's profile names, which is the exact thing the relay and media
 * permission lists exist to prevent. So it says "claimed", which is true, rather
 * than "verified", which would not be.
 */
@Composable
private fun Nip05Line(nip05: String) {
    // `_@example.com` is NIP-05's way of writing the domain's root identity, and
    // showing the underscore helps nobody.
    val shown = nip05.removePrefix("_@")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "claimed, not checked",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The npub and the other addresses, as things to do rather than things to read.
 *
 * The npub used to be printed in full: 63 monospace characters wrapping over
 * three lines, dominating the card and useful to nobody, since the one thing
 * anybody wants to do with a key is copy it or show it to a phone.
 */
@Composable
private fun IdentityChips(
    npub: String,
    lightning: String?,
    website: String?,
    onCopy: (String) -> Unit,
    onShowQr: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        IdentityChip(
            label = shortenNpub(npub),
            monospace = true,
            // Copies the whole key, not the shortened form on the chip.
            onClick = { onCopy(npub) },
        )
        IconButton(onClick = onShowQr, modifier = Modifier.size(36.dp)) {
            Icon(WayfarerIcons.Qr, contentDescription = "Show this npub as a code", modifier = Modifier.size(20.dp))
        }
        // Parsed and editable since the profile editor was written, and never
        // once drawn until now.
        lightning?.let { IdentityChip(label = "⚡ $it", onClick = { onCopy(it) }) }
        website?.let { IdentityChip(label = it, onClick = { onCopy(it) }) }
    }
}

/**
 * What this app actually knows about a person's presence, and nothing more.
 *
 * No follower count: nostr has no cheap way to compute one and this app makes no
 * attempt, so the honest options were to omit it or to invent it.
 */
@Composable
private fun FactsRow(
    controller: AppController,
    pubKey: PubKey,
    notesFound: Int,
    articleCount: Int,
    relayEntries: List<RelayListEntry>,
) {
    val relays by controller.relays.state.collectAsStateWithLifecycle()
    val published by controller.publishedFollows.collectAsStateWithLifecycle()
    val local by controller.localFollows.collectAsStateWithLifecycle()

    val allowed = relayEntries.count { relays.approvalOf(it.url) == RelayApproval.Allowed }

    val facts =
        buildList {
            add(if (notesFound == 1) "1 note found" else "$notesFound notes found")
            if (articleCount > 0) add(if (articleCount == 1) "1 article" else "$articleCount articles")
            if (relayEntries.isNotEmpty()) add("reachable on $allowed of ${relayEntries.size} relays")
            when {
                pubKey in published && pubKey in local -> add("followed publicly and here")
                pubKey in published -> add("on your public list")
                pubKey in local -> add("followed on this phone")
            }
        }

    Text(
        facts.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Your own profile's actions: one button, and a menu for the rest.
 *
 * Three equally-weighted buttons made "edit my profile" compete with "settings"
 * and "manage who I follow", which are not the same kind of thing. Settings in
 * particular stays off the one-tap path deliberately — logging out erases the
 * key, so it lives next to the backup that makes it survivable.
 */
@Composable
private fun OwnProfileActions(controller: AppController) {
    var menu by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { controller.go(Screen.EditProfile) }) { Text("Edit profile") }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(WayfarerIcons.More, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Manage who I follow") },
                    onClick = {
                        menu = false
                        controller.go(Screen.Follows)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = {
                        menu = false
                        controller.go(Screen.Settings)
                    },
                )
            }
        }
    }
}


/**
 * The nudge that used to be missing entirely: relays are allowed, posting works,
 * and yet nobody can find the posts because no kind 10002 exists.
 */
@Composable
private fun RelayListPrompt(onPublish: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PostHorizontalPadding, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("One thing left", style = MaterialTheme.typography.titleSmall)
            Text(
                "You can post, but nobody knows where to look for your posts yet. A public relay list tells " +
                    "other people's apps which relays you use — it is one small signed note, and without it " +
                    "only people who already share a relay with you will see anything you write.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onPublish) { Text("Set up where others find me") }
        }
    }
}

/**
 * The two ways to follow somebody, said plainly.
 *
 * Not one button with a hidden default: the difference between them is whether
 * a signed note naming this person is broadcast to relays, which is not
 * something to decide on the reader's behalf.
 */
@Composable
private fun FollowControls(
    pubKey: PubKey,
    controller: AppController,
) {
    val published by controller.publishedFollows.collectAsStateWithLifecycle()
    val local by controller.localFollows.collectAsStateWithLifecycle()
    val isPublic = pubKey in published
    val isLocal = pubKey in local

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isPublic) {
                OutlinedButton(onClick = { controller.unfollowPublicly(pubKey) }) { Text("Unfollow publicly") }
            } else {
                Button(onClick = { controller.followPublicly(pubKey) }) { Text("Follow publicly") }
            }
            if (isLocal) {
                OutlinedButton(onClick = { controller.unfollowLocally(pubKey) }) { Text("Remove from this phone") }
            } else {
                OutlinedButton(onClick = { controller.followLocally(pubKey) }) { Text("Follow on this phone") }
            }
        }
        Text(
            if (isPublic || isLocal) {
                "A public follow is listed in a signed note anyone can read. A follow on this phone is told to nobody."
            } else {
                "Following publicly republishes your whole follow list. Following on this phone tells no relay anything."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Not a refusal: the follow still publishes. But a list built from
        // nothing replaces the real one everywhere, and that is worth knowing
        // before the tap rather than after it.
        if (!isPublic && !controller.publicFollowListKnown) {
            Text(
                "Your existing follow list has not loaded yet, so following publicly now would publish a list " +
                    "containing only this person.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * A profile's advertised relays (NIP-65, kind 10002).
 *
 * On your own profile this is the way in to editing and publishing it, and it
 * says plainly that it is public and separate from the app's permission list —
 * the two being confused for each other is the whole reason it lives here
 * rather than beside the permission switches.
 */
@Composable
private fun AdvertisedRelaysCard(
    entries: List<RelayListEntry>,
    isMe: Boolean,
    controller: AppController,
    ownerName: String,
    onManage: () -> Unit,
    initiallyExpanded: Boolean = false,
) {
    val relays by controller.relays.state.collectAsStateWithLifecycle()
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }

    val approvals = entries.map { relays.approvalOf(it.url) }
    val allowedCount = approvals.count { it == RelayApproval.Allowed }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Collapsed by default: on somebody else's profile this is reference
            // material, and the one thing worth knowing at a glance — whether
            // this app can actually reach them — fits in the badge.
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isMe) "Where others find you" else "Where this person says to find them",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entries.isNotEmpty()) {
                    CoverageBubble(allowed = allowedCount, total = entries.size)
                }
            }

            if (entries.isEmpty()) {
                Text(
                    if (isMe) {
                        "You have not published a relay list, so nobody who does not already share a relay " +
                            "with you can find your posts."
                    } else {
                        "No relay list found for this person yet, so their posts have to be guessed at rather " +
                            "than fetched from where they actually publish."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (expanded) {
                for ((entry, approval) in entries.sortedBy { it.url.display() }.zip(approvals)) {
                    AdvertisedRelayRow(entry, approval) {
                        controller.openRelayDetail(
                            entry.url,
                            because = "$ownerName says they can be found here",
                        )
                    }
                }
            }

            if (isMe) {
                Text(
                    "This is a public note signed by your key (NIP-65). It is not the same list as the relays " +
                        "this phone is allowed to connect to.",
                    style = MaterialTheme.typography.labelSmall,
                )
                // Red — they advertise relays and none of them are allowed here —
                // is the one state where this button is the wrong next step: the
                // fix is approving a relay, which a row above does in one tap.
                // With no list at all there is no bubble and no other way in, so
                // the button stays.
                if (entries.isEmpty() || allowedCount > 0) {
                    Button(onClick = onManage) {
                        Text(if (entries.isEmpty()) "Set up my relay list" else "Manage my relay list")
                    }
                }
            }
        }
    }
}

/**
 * How much of what this person advertises the app can actually reach.
 *
 * Red is the state that matters: they publish somewhere and none of it is
 * allowed here, so their posts cannot arrive however often they write.
 *
 * The three states are drawn in container roles rather than in literal hex. The
 * green this used to hardcode measured 2.9:1 against the dark background — it
 * was unreadable in dark mode, because a fixed colour cannot follow the theme.
 * Each state also carries its own glyph, so the one signal here does not rest on
 * telling green from amber from red.
 */
@Composable
private fun CoverageBubble(
    allowed: Int,
    total: Int,
) {
    val colors = MaterialTheme.colorScheme
    val (background, content, rest) =
        when {
            allowed == 0 -> Triple(colors.errorContainer, colors.onErrorContainer, WayfarerIcons.Close to "$total")
            allowed < total ->
                Triple(colors.secondaryContainer, colors.onSecondaryContainer, WayfarerIcons.HalfCheck to "$allowed of $total")
            else -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, WayfarerIcons.Check to "$total")
        }
    val (icon, label) = rest

    Row(
        modifier = Modifier.background(background, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
private fun AdvertisedRelayRow(
    entry: RelayListEntry,
    approval: RelayApproval,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val allowed = approval == RelayApproval.Allowed
        Icon(
            if (allowed) WayfarerIcons.Check else WayfarerIcons.Close,
            contentDescription = if (allowed) "Allowed here" else "Not allowed here",
            tint = if (allowed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.url.display(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.direction() + " · " + approval.describe(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun RelayApproval.describe(): String =
    when (this) {
        RelayApproval.Allowed -> "allowed here"
        RelayApproval.Waiting -> "waiting for your decision"
        RelayApproval.Blocked -> "blocked here"
        RelayApproval.Unknown -> "not in your list — tap to decide"
    }

private fun RelayListEntry.direction(): String =
    when {
        read && write -> "posts and replies"
        write -> "posts"
        read -> "replies and mentions"
        else -> "nothing"
    }

/**
 * Editing your own profile, grouped by what each field is for.
 *
 * Eight identical text fields in a column gave a lightning address the same
 * weight as a display name and left the reader to work out which of them anybody
 * else would ever see.
 */
@Composable
fun EditProfileScreen(controller: AppController) {
    val initial = remember { controller.ownProfileDraft() }
    var draft by remember { mutableStateOf(initial) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(title = { Text("Edit profile", style = MaterialTheme.typography.titleLarge) })
        Column(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FieldGroup("You") {
                Field("Display name", draft.displayName) { draft = draft.copy(displayName = it) }
                Field("Short handle", draft.name) { draft = draft.copy(name = it) }
                Field("About", draft.about, singleLine = false) { draft = draft.copy(about = it) }
            }

            FieldGroup("Pictures") {
                Field("Picture URL", draft.picture) { draft = draft.copy(picture = it) }
                Field("Banner URL", draft.banner) { draft = draft.copy(banner = it) }
                Text(
                    "Nostr stores no pictures — these are web addresses, and whoever reads your profile has to " +
                        "fetch them from wherever they point. Other people decide for themselves whether to load " +
                        "from that server, the same way you do in Pictures.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FieldGroup("Finding you") {
                Field("NIP-05 identifier", draft.nip05) { draft = draft.copy(nip05 = it) }
                Field("Website", draft.website) { draft = draft.copy(website = it) }
                Field("Lightning address", draft.lud16) { draft = draft.copy(lud16 = it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Saving publishes a kind 0 to your write relays. Fields other clients set that Wayfarer does not " +
                    "show are carried over unchanged.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.saveProfile(draft) }) { Text("Publish profile") }
                TextButton(onClick = { controller.back() }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun FieldGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}
