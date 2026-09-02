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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.ScreenHeader
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.RelayListRow

/**
 * The account's own NIP-65 relay list: where other people should look for it.
 *
 * The screen exists as its own destination because the thing it edits is not the
 * relay permission list, and the two being one screen is what made them feel
 * like one setting. This one is a signed, public event on the network. The other
 * is a private note to this app about what it may connect to. Nothing here is
 * sent until Publish is pressed.
 */
@Composable
fun RelayListScreen(controller: AppController) {
    val state by controller.relayList.relayList.collectAsStateWithLifecycle()
    val account by controller.account.collectAsStateWithLifecycle()

    var newRelay by remember { mutableStateOf("") }
    var explaining by remember { mutableStateOf(false) }

    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text("Why publish a relay list?") },
            text = {
                Text(
                    "Nostr has no directory. If somebody has your npub but does not happen to use the same " +
                        "relays as you, there is nothing to look you up in — and they see nothing of yours.\n\n" +
                        "A relay list (NIP-65) fixes that. It is a small note, signed by your key, that says " +
                        "\"my posts go to these relays, and you can reach me at these\". Other people's apps read " +
                        "it and then know exactly where to fetch your posts, and where to send you replies and " +
                        "mentions — instead of guessing, or spraying every relay they know.\n\n" +
                        "Two lists, two jobs. The Relays tab is private to this phone: which servers this app " +
                        "may connect to. This list is public: where the world should look for you. A relay can " +
                        "be in one and not the other, and this screen tells you when that happens.\n\n" +
                        "What is public: the relay addresses themselves, and the fact that they are yours. " +
                        "That is enough for someone to know roughly where you spend your time, which is the " +
                        "trade for being findable at all. Nothing else — no posts, no keys, no follows — is in " +
                        "this note.\n\n" +
                        "Publishing again replaces the old one: it is a replaceable event, so the newest wins.",
                )
            },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text("Got it") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = { Text("Where others find you", style = MaterialTheme.typography.titleLarge) })
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "This is the public note that tells other people's apps where your posts are and where to " +
                            "reach you. It is signed by your key and anyone can read it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Different from the Relays tab, which is this phone's private list of what Wayfarer may " +
                            "connect to. Changing that publishes nothing; changing this does, when you press Publish.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { explaining = true }) { Text("Why does this matter?") }
                }
            }

            item { StatusCard(controller, state.loading, state.loaded, state.publishedAt, state.isSuggestion, state.edited) }

            if (account?.canSign == false) {
                item {
                    Text(
                        "This account was added with an npub, so there is no key here to sign with. You can see " +
                            "what it publishes, but not change it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

            if (state.loaded && state.rows.isEmpty()) {
                item {
                    Text(
                        "No relays in the list. Add one below, or fill it in from the relays this phone is " +
                            "already allowed to use.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            items(state.rows, key = { it.url.url }) { row ->
                RelayListEntryCard(
                    row = row,
                    editable = state.canPublish,
                    onChange = { read, write -> controller.relayList.setPermissions(row.url, read, write) },
                    onRemove = { controller.relayList.remove(row.url) },
                    onAllowHere = { controller.relayList.allowHere(row.url) },
                )
            }

            if (state.canPublish) {
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add a relay to the list", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = newRelay,
                            onValueChange = { newRelay = it },
                            label = { Text("wss://relay.example.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    controller.relayList.add(newRelay, read = true, write = false)
                                    newRelay = ""
                                },
                                enabled = newRelay.isNotBlank(),
                            ) { Text("Reach me here") }
                            Button(
                                onClick = {
                                    controller.relayList.add(newRelay, read = true, write = true)
                                    newRelay = ""
                                },
                                enabled = newRelay.isNotBlank(),
                            ) { Text("Both") }
                        }
                        OutlinedButton(
                            onClick = { controller.relayList.fillFromAllowedRelays() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Fill in from the relays I allow here") }
                        Text(
                            "A starting point, not a rule: the two lists are yours to keep as different as you " +
                                "like. Nothing is published until you press Publish.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { controller.relayList.publish() },
                            enabled = state.rows.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.publishedAt == null) "Publish my relay list" else "Publish the changes") }
                        Text(
                            "Signs a kind 10002 event and sends it to the relays you allow for posting. It " +
                                "replaces any list you published before.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state.edited) {
                            TextButton(
                                onClick = { controller.relayList.discardChanges() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Discard my changes") }
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun StatusCard(
    controller: AppController,
    loading: Boolean,
    loaded: Boolean,
    publishedAt: Long?,
    isSuggestion: Boolean,
    edited: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                loading || !loaded -> Text("Looking for your published list…", style = MaterialTheme.typography.titleSmall)
                isSuggestion ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("You have not published a relay list", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Nobody who does not already share a relay with you can find your posts. The rows " +
                                "below are a suggestion drawn from the relays this phone allows — nothing has " +
                                "been published.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                else ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Your list is published", style = MaterialTheme.typography.titleSmall)
                        Text(
                            // Bound rather than asserted: a list can be known to
                            // exist without its timestamp having been read yet,
                            // and a crash is a worse answer than a sentence.
                            publishedAt
                                ?.let { "Last published ${controller.timeAgo(it)}. " }
                                .orEmpty() + "Other people's apps read this to find you.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
            }
            if (edited) {
                Text(
                    "You have unpublished changes. They exist only on this phone until you press Publish.",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * One advertised relay.
 *
 * The two flags are NIP-65's own, and they are about direction of traffic
 * rather than permission: "reach me here" is the `read` marker (your inbox),
 * "my posts go here" is `write` (your outbox). Both unset is not a state — it
 * would say nothing — so clearing both removes the row.
 */
@Composable
private fun RelayListEntryCard(
    row: RelayListRow,
    editable: Boolean,
    onChange: (read: Boolean, write: Boolean) -> Unit,
    onRemove: () -> Unit,
    onAllowHere: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.url.display(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)

            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = row.read,
                        onClick = { onChange(!row.read, row.write) },
                        label = { Text("Reach me here") },
                    )
                    FilterChip(
                        selected = row.write,
                        onClick = { onChange(row.read, !row.write) },
                        label = { Text("My posts go here") },
                    )
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }

            Text(advertisementSummary(row.read, row.write), style = MaterialTheme.typography.labelSmall)

            // The one place the two lists have to be looked at together: advertising
            // a relay this app may not touch is allowed, and usually a mistake.
            if (!row.allowedHere) {
                Text(
                    "This phone is not allowed to connect to this relay, so Wayfarer will not fetch anything " +
                        "people send you there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onAllowHere) { Text("Allow it here too") }
            }
        }
    }
}

private fun advertisementSummary(
    read: Boolean,
    write: Boolean,
): String =
    when {
        read && write -> "You are telling people your posts are here, and that replies and mentions reach you here."
        write -> "You are telling people to look for your posts here."
        read -> "You are telling people to send replies and mentions here."
        else -> "Says nothing, so it will not be published."
    }
