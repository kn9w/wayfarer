package app.wayfarer.android.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.Profile

@Composable
fun HomeScreen(controller: AppController) {
    val feed by controller.feed.collectAsStateWithLifecycle()
    var lookup by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lookup,
                    onValueChange = { lookup = it },
                    label = { Text("Look up an npub") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { controller.openProfileByKey(lookup) },
                    enabled = lookup.isNotBlank(),
                ) { Text("Open") }
            }
        }

        item {
            OutlinedButton(onClick = { controller.refreshFeed() }, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh")
            }
        }

        // The outbox model's failure mode made visible: an author whose relays we
        // are not allowed to reach is named, not silently missing from the feed.
        if (feed.unreachableAuthors.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${feed.unreachableAuthors.size} of the people you follow publish only to relays you have not " +
                                "approved, so their notes are missing. Their relays are waiting in the Relays tab.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (feed.loaded && feed.notes.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Approve some relays, then follow someone — or post the first note yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(feed.notes, key = { it.id.hex }) { note ->
            NoteCard(
                note = note,
                profile = feed.profiles[note.author],
                onOpenAuthor = { controller.openProfile(note.author) },
            )
        }

        if (feed.loaded) {
            item {
                Text(
                    "Queried ${feed.relaysQueried} relays, chosen from where these authors say they publish." +
                        if (feed.guessedAuthors.isEmpty()) {
                            ""
                        } else {
                            " ${feed.guessedAuthors.size} of them publish no relay list, so those were guessed " +
                                "from your own approved relays rather than routed."
                        },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
fun NoteCard(
    note: Note,
    profile: Profile?,
    onOpenAuthor: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                profile?.bestName() ?: note.author.abbreviated(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.clickable(onClick = onOpenAuthor),
            )
            Text(note.content, style = MaterialTheme.typography.bodyMedium)
            // Provenance: which relay actually delivered this note. Under the
            // outbox model that is a meaningful thing to be able to see.
            Text(
                "seen on " + note.seenOn.joinToString(", ") { it.display() }.ifBlank { "this device" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
