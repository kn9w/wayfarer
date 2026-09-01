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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
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
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.PendingRelay
import app.wayfarer.core.model.RelayGrant
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.relay.RelayInfoService

/**
 * The relay permission screen — the app's most important surface.
 *
 * Three sections, matching the three states a relay can be in: approved (with
 * independent read and write switches), awaiting approval (with the reason the
 * app wanted it), and refused.
 */
@Composable
fun RelayScreen(controller: AppController) {
    val state by controller.relays.state.collectAsStateWithLifecycle()
    val connected by controller.connectedRelays.collectAsStateWithLifecycle()
    val relayInfo by controller.relayInfo.collectAsStateWithLifecycle()
    val infoPrompt by controller.relayInfoPrompt.collectAsStateWithLifecycle()

    var newRelay by remember { mutableStateOf("") }

    // Reading a relay's NIP-11 document is an HTTPS request to that relay. For a
    // relay the user has not approved, that is a connection they have not
    // sanctioned, so it is spelled out and confirmed rather than just happening.
    infoPrompt?.let { url ->
        AlertDialog(
            onDismissRequest = controller::dismissRelayInfoPrompt,
            title = { Text("Contact ${url.display()}?") },
            text = {
                Text(
                    "Reading this relay's information document opens an HTTPS connection to " +
                        "${url.display()}. The relay is not approved: nothing will be published to it and " +
                        "no notes will be fetched from it, but it will see this request and your IP address.",
                )
            },
            confirmButton = {
                Button(onClick = controller::confirmRelayInfoFetch) { Text("Fetch info") }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissRelayInfoPrompt) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Wayfarer opens a connection only to relays listed as approved below. " +
                    "Everything else it is asked to reach — by your relay list, by the people you follow, " +
                    "by a relay hint on an event — waits here until you decide.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            SectionHeader("Approved", state.approved.size)
        }
        if (state.approved.isEmpty()) {
            item {
                Text(
                    "Nothing approved yet, so nothing is connected. Approve a relay below to start.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(state.approved, key = { it.url.url }) { grant ->
            ApprovedRelayCard(
                grant = grant,
                isConnected = grant.url in connected,
                info = relayInfo[grant.url],
                onChange = { read, write -> controller.relays.setPermissions(grant.url, read, write) },
                onRemove = { controller.relays.forget(grant.url) },
                onFetchInfo = { controller.requestRelayInfo(grant.url) },
            )
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Awaiting your approval", state.pending.size) }
        if (state.pending.isEmpty()) {
            item { Text("Nothing waiting.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.pending, key = { it.url.url }) { pending ->
            PendingRelayCard(
                pending = pending,
                info = relayInfo[pending.url],
                onApprove = { read, write -> controller.relays.setPermissions(pending.url, read, write) },
                onDeny = { controller.relays.deny(pending.url) },
                onFetchInfo = { controller.requestRelayInfo(pending.url) },
            )
        }

        if (state.denied.isNotEmpty()) {
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Refused", state.denied.size) }
            items(state.denied, key = { it.url }) { url ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(url.display(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { controller.relays.forget(url) }) { Text("Un-refuse") }
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a relay", style = MaterialTheme.typography.titleSmall)
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
                            controller.relays.add(newRelay, read = true, write = false)
                            newRelay = ""
                        },
                        enabled = newRelay.isNotBlank(),
                    ) { Text("Add for reading") }
                    Button(
                        onClick = {
                            controller.relays.add(newRelay, read = true, write = true)
                            newRelay = ""
                        },
                        enabled = newRelay.isNotBlank(),
                    ) { Text("Add for read + write") }
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Advertise these relays (NIP-65)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Publishes a kind 10002 saying you read from your read relays and publish to your write relays. " +
                        "This is how other people's clients find your notes, so publish it after any change here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { controller.relays.publishRelayList() },
                    enabled = state.approved.isNotEmpty(),
                ) { Text("Publish my relay list") }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
) {
    Text("$title ($count)", style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ApprovedRelayCard(
    grant: RelayGrant,
    isConnected: Boolean,
    info: RelayInfoService.Entry?,
    onChange: (read: Boolean, write: Boolean) -> Unit,
    onRemove: () -> Unit,
    onFetchInfo: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(grant.url.display(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (isConnected) "connected" else "not connected",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = grant.read,
                    onClick = { onChange(!grant.read, grant.write) },
                    label = { Text("Read") },
                )
                FilterChip(
                    selected = grant.write,
                    onClick = { onChange(grant.read, !grant.write) },
                    label = { Text("Write") },
                )
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            RelayInfoPanel(info, onFetchInfo)
        }
    }
}

@Composable
private fun PendingRelayCard(
    pending: PendingRelay,
    info: RelayInfoService.Entry?,
    onApprove: (read: Boolean, write: Boolean) -> Unit,
    onDeny: () -> Unit,
    onFetchInfo: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pending.url.display(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            for (reason in pending.reasons) {
                Text("· ${reason.describe()}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onApprove(true, false) }) { Text("Read") }
                Button(onClick = { onApprove(true, true) }) { Text("Read + write") }
                TextButton(onClick = onDeny) { Text("Refuse") }
            }
            RelayInfoPanel(info, onFetchInfo)
        }
    }
}

/**
 * NIP-11 self-description, shown where the approve/refuse decision is made.
 *
 * "Requires payment" and "requires auth" in particular are the difference
 * between a relay that will quietly ignore everything this app sends and one
 * that will work.
 */
@Composable
private fun RelayInfoPanel(
    info: RelayInfoService.Entry?,
    onFetch: () -> Unit,
) {
    when (info) {
        null -> TextButton(onClick = onFetch) { Text("Fetch relay info") }
        RelayInfoService.Entry.Loading -> Text("Reading relay info…", style = MaterialTheme.typography.bodySmall)
        is RelayInfoService.Entry.Failed ->
            Column {
                Text(info.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onFetch) { Text("Try again") }
            }
        is RelayInfoService.Entry.Loaded ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                info.info.name?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                info.info.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val software = listOfNotNull(info.info.software, info.info.version).joinToString(" ")
                if (software.isNotBlank()) {
                    Text(software, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                if (info.info.supportedNips.isNotEmpty()) {
                    Text(
                        "NIPs " + info.info.supportedNips.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                val flags =
                    listOfNotNull(
                        "requires auth".takeIf { info.info.authRequired },
                        "requires payment".takeIf { info.info.paymentRequired },
                    )
                if (flags.isNotEmpty()) {
                    Text(flags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                }
                info.info.postingPolicy?.let { Text("Posting policy: $it", style = MaterialTheme.typography.labelSmall) }
            }
    }
}

private fun DiscoveryReason.describe(): String {
    val prefix =
        when (source) {
            DiscoverySource.BOOTSTRAP -> "suggested by the app"
            DiscoverySource.USER_ENTERED -> "you added it"
            DiscoverySource.OWN_RELAY_LIST -> "in your own relay list"
            DiscoverySource.AUTHOR_RELAY_LIST -> "in someone's relay list"
            DiscoverySource.EVENT_HINT -> "hinted by an event"
            DiscoverySource.CONTACT_LIST -> "in your contact list"
        }
    return if (detail.isNullOrBlank()) prefix else "$prefix — $detail"
}
