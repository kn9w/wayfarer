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

@Composable
fun ProfileScreen(
    controller: AppController,
    pubKey: PubKey,
) {
    val viewed by controller.viewedProfile.collectAsStateWithLifecycle()
    val account by controller.account.collectAsStateWithLifecycle()
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
                            OutlinedButton(onClick = { controller.logout() }) { Text("Log out") }
                        }
                    }
                }
            }
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

        items(viewed?.notes.orEmpty(), key = { it.id.hex }) { note ->
            NoteCard(note = note, profile = viewed?.profile, onOpenAuthor = {})
        }

        if (viewed?.loading == false && viewed?.notes.orEmpty().isEmpty()) {
            item { Text("No notes found.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
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
