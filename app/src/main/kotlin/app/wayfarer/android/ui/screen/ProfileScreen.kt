package app.wayfarer.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.ScreenHeader
import app.wayfarer.android.ui.icons.WayfarerIcons
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.RelayApproval
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.nostr.RelayListEntry

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
    var lookup by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = PostHorizontalPadding)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        controller.displayName(pubKey),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    viewed?.profile?.about?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    viewed?.profile?.nip05?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    viewed?.profile?.website?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        controller.npubFor(pubKey),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (isMe) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { controller.go(Screen.EditProfile) }) { Text("Edit profile") }
                            // Logging out erases the key, so it lives in settings
                            // next to the backup that makes it survivable — not one
                            // tap from the profile.
                            OutlinedButton(onClick = { controller.go(Screen.Settings) }) { Text("Settings") }
                        }
                        OutlinedButton(onClick = { controller.go(Screen.Follows) }) { Text("Manage who I follow") }
                    } else {
                        FollowControls(pubKey, controller)
                    }
                }
            }
        }

        // Where this person says they can be found — the public NIP-65 list, which
        // is a different thing from the relays this phone is allowed to use. On
        // your own profile it is editable; on anyone else's it is what routing
        // already knows, shown rather than hidden.
        // Moved here from the Relays tab, which is now exclusively the list of
        // what this app may connect to. Everything about the public NIP-65 list
        // — what it is, the nudge to publish one, the editor — lives on your own
        // profile, because it is a fact about you rather than about this phone.
        if (isMe && offerRelayList) {
            item { RelayListPrompt(onPublish = controller::openRelayList) }
        }

        item {
            Box(Modifier.padding(horizontal = PostHorizontalPadding)) {
                AdvertisedRelaysCard(
                    // From the reactive cache rather than a synchronous read:
                    // advertisedRelaysFor peeks at a map with no flow, so a
                    // relay list arriving after first composition never showed.
                    entries = relayLists[pubKey]?.entries.orEmpty(),
                    isMe = isMe,
                    controller = controller,
                    ownerName = controller.displayName(pubKey),
                    onManage = controller::openRelayList,
                )
            }
        }

        if (viewed?.unreachable == true) {
            item {
                Text(
                    "This person publishes only to relays you have not approved, so nothing of theirs can be fetched. " +
                        "Their relays are waiting in the Relays tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = PostHorizontalPadding),
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

        val authorArticles = articles.filter { it.author == pubKey }
        if (authorArticles.isNotEmpty()) {
            item { ProfileSectionHeader("Articles") }
            items(authorArticles, key = { it.address }) { article ->
                ArticleRow(
                    article = article,
                    controller = controller,
                    onOpen = { controller.go(Screen.ReadArticle(article.address)) },
                )
                PostDivider()
            }
            item { ProfileSectionHeader("Notes") }
        }

        items(viewed?.notes.orEmpty(), key = { it.id.hex }) { note ->
            NoteRow(
                note = note,
                controller = controller,
                onOpen = { controller.go(Screen.ReadNote(note.id)) },
            )
            PostDivider()
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = PostHorizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HorizontalDivider()
                Text("Find somebody", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lookup,
                        onValueChange = { lookup = it },
                        label = { Text("npub or nprofile") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { controller.openProfileByKey(lookup) },
                        enabled = lookup.isNotBlank(),
                    ) { Text("Open") }
                }
                Text(
                    "Wayfarer has no directory to search — nobody does. A person is an npub, and somebody " +
                        "has to give you theirs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (viewed?.loading == false && viewed?.notes.orEmpty().isEmpty()) {
            item {
                Text(
                    "No notes found.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = PostHorizontalPadding),
                )
            }
        }
    }
}

@Composable
private fun ProfileSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = PostHorizontalPadding),
    )
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
) {
    val relays by controller.relays.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

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
 */
@Composable
private fun CoverageBubble(
    allowed: Int,
    total: Int,
) {
    val colors = MaterialTheme.colorScheme
    val (background, label) =
        when {
            allowed == 0 -> colors.error to "$total"
            allowed < total -> Color(0xFFB4690E) to "$allowed of $total"
            else -> Color(0xFF2E6B34) to "$total"
        }

    Row(
        modifier = Modifier.background(background, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (allowed == total) {
            Icon(
                WayfarerIcons.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
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
            tint = if (allowed) Color(0xFF2E6B34) else MaterialTheme.colorScheme.error,
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
            Field("Name", draft.name) { draft = draft.copy(name = it) }
            Field("Display name", draft.displayName) { draft = draft.copy(displayName = it) }
            Field("About", draft.about, singleLine = false) { draft = draft.copy(about = it) }
            Field("Picture URL", draft.picture) { draft = draft.copy(picture = it) }
            Field("Banner URL", draft.banner) { draft = draft.copy(banner = it) }
            Field("Website", draft.website) { draft = draft.copy(website = it) }
            Field("NIP-05 identifier", draft.nip05) { draft = draft.copy(nip05 = it) }
            Field("Lightning address", draft.lud16) { draft = draft.copy(lud16 = it) }

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
