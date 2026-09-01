package app.wayfarer.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.viewmodel.AppController
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
    val isMe = account?.pubKey == pubKey

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        viewed?.profile?.bestName() ?: pubKey.abbreviated(),
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
                    }
                }
            }
        }

        // Where this person says they can be found — the public NIP-65 list, which
        // is a different thing from the relays this phone is allowed to use. On
        // your own profile it is editable; on anyone else's it is what routing
        // already knows, shown rather than hidden.
        item {
            AdvertisedRelaysCard(
                entries = controller.advertisedRelaysFor(pubKey),
                isMe = isMe,
                onManage = controller::openRelayList,
            )
        }

        if (viewed?.unreachable == true) {
            item {
                Text(
                    "This person publishes only to relays you have not approved, so nothing of theirs can be fetched. " +
                        "Their relays are waiting in the Relays tab.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

        val authorArticles = articles.filter { it.author == pubKey }
        if (authorArticles.isNotEmpty()) {
            item { Text("Articles", style = MaterialTheme.typography.titleSmall) }
            items(authorArticles, key = { it.address }) { article ->
                ArticleCard(
                    article = article,
                    authorName = viewed?.profile?.bestName() ?: pubKey.abbreviated(),
                    onOpen = { controller.go(Screen.ReadArticle(article.address)) },
                )
            }
            item { Text("Notes", style = MaterialTheme.typography.titleSmall) }
        }

        items(viewed?.notes.orEmpty(), key = { it.id.hex }) { note ->
            NoteCard(note = note, profile = viewed?.profile, onOpenAuthor = {})
        }

        if (viewed?.loading == false && viewed?.notes.orEmpty().isEmpty()) {
            item { Text("No notes found.", style = MaterialTheme.typography.bodyMedium) }
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
    onManage: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (isMe) "Where others find you" else "Where this person says to find them",
                style = MaterialTheme.typography.titleSmall,
            )

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
            } else {
                for (entry in entries.sortedBy { it.url.display() }) {
                    Text(
                        entry.url.display() + " · " + entry.direction(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            if (isMe) {
                Text(
                    "This is a public note signed by your key (NIP-65). It is not the same list as the relays " +
                        "this phone is allowed to connect to.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Button(onClick = onManage) {
                    Text(if (entries.isEmpty()) "Set up my relay list" else "Manage my relay list")
                }
            }
        }
    }
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
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
            TextButton(onClick = { controller.go(Screen.Home) }) { Text("Cancel") }
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
