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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.Profile

@Composable
fun HomeScreen(controller: AppController) {
    val feed by controller.feed.collectAsStateWithLifecycle()
    val articles by controller.articles.collectAsStateWithLifecycle()
    val account by controller.account.collectAsStateWithLifecycle()
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
                    label = { Text("Find someone by npub") },
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

        // What this feed actually is. Browsing a relay and reading your follows
        // are different claims, and a new account is always doing the first.
        if (feed.loaded && feed.browsingRelays.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (account == null) {
                                "You are reading without an account, so this is simply what is on the relays " +
                                    "you allowed — not a feed of anyone in particular."
                            } else {
                                "You do not follow anyone yet, so this is what is on the relays you allowed " +
                                    "rather than a feed of people you chose."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Find someone by pasting their npub above. Wayfarer does not have a directory to " +
                                "search — nobody does.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Nothing to show yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (feed.relaysQueried == 0) {
                                "Wayfarer is not allowed to talk to any relay, so it has nowhere to read from. " +
                                    "Allowing one is the whole of the setup."
                            } else {
                                "The relays you allowed returned nothing. Try another relay, or look somebody " +
                                    "up by npub above and read them directly."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { controller.go(Screen.Relays) }) { Text("Choose relays") }
                    }
                }
            }
        }

        if (articles.isNotEmpty()) {
            item { Text("Articles", style = MaterialTheme.typography.titleSmall) }
            items(articles, key = { it.address }) { article ->
                ArticleCard(
                    article = article,
                    authorName = feed.profiles[article.author]?.bestName() ?: article.author.abbreviated(),
                    onOpen = { controller.go(Screen.ReadArticle(article.address)) },
                )
            }
            item { Text("Notes", style = MaterialTheme.typography.titleSmall) }
        }

        items(feed.notes, key = { it.id.hex }) { note ->
            NoteCard(
                note = note,
                profile = feed.profiles[note.author],
                onOpenAuthor = { controller.openProfile(note.author) },
            )
        }

        if (feed.loaded && feed.browsingRelays.isNotEmpty()) {
            item {
                Text(
                    "Read directly from ${feed.relaysQueried} relay(s) you allowed.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else if (feed.loaded) {
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
