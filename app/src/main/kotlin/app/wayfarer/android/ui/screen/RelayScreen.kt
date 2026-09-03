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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.ScreenHeader
import app.wayfarer.android.ui.icons.WayfarerIcons
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.RelayScreenState
import app.wayfarer.core.model.DiscoveryReason
import app.wayfarer.core.model.DiscoverySource
import app.wayfarer.core.model.RelayGrant
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.relay.RelayInfoService

/**
 * The relay permission screen — the app's most important surface.
 *
 * Every relay is one row of the same height, whatever its state and however much
 * it has to say for itself. That is not a style choice: outbox routing turns a
 * few hundred follows into a few hundred queued relays, and rows that grew with
 * their content — a line per discovery reason, a NIP-11 block that unfolded in
 * place — made the list impossible to scroll to the end of. Everything variable
 * now lives in the sheet a row opens.
 *
 * The wording carries as much weight as the switches. "Read or read + write for
 * nos.lol?" is a question only somebody who already knows what a relay is can
 * answer, so every control here says what it *does* — download posts from this
 * server, put your posts on it — rather than naming the permission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScreen(controller: AppController) {
    val state by controller.relays.state.collectAsStateWithLifecycle()
    val connected by controller.connectedRelays.collectAsStateWithLifecycle()
    val relayInfo by controller.relayInfo.collectAsStateWithLifecycle()
    val infoPrompt by controller.relayInfoPrompt.collectAsStateWithLifecycle()
    var explaining by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(RelayFilter.All) }
    var opened by remember { mutableStateOf<RelayUrl?>(null) }

    // Something elsewhere — a relay named on somebody's profile — asked for this
    // relay's details. Consumed rather than observed, so going back and forward
    // again does not re-open the sheet by itself.
    val focus by controller.relayFocus.collectAsStateWithLifecycle()
    LaunchedEffect(focus) {
        focus?.let {
            opened = it
            controller.clearRelayFocus()
        }
    }

    val all = remember(state) { state.rows() }
    val shown =
        remember(all, query, filter) {
            all.filter { filter.accepts(it.status) && it.url.display().contains(query.trim(), ignoreCase = true) }
        }

    // Reading a relay's NIP-11 document is an HTTPS request to that relay. For a
    // relay the user has not approved, that is a connection they have not
    // sanctioned, so it is spelled out and confirmed rather than just happening.
    infoPrompt?.let { url ->
        AlertDialog(
            onDismissRequest = controller::dismissRelayInfoPrompt,
            title = { Text("Contact ${url.display()}?") },
            text = {
                Text(
                    "To describe itself, this server has to be asked. That opens a connection to " +
                        "${url.display()}: it will see the request and your IP address. It is not allowed to " +
                        "do anything else — no posts are sent to it, and none are fetched from it.",
                )
            },
            confirmButton = {
                Button(onClick = controller::confirmRelayInfoFetch) { Text("Ask it") }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissRelayInfoPrompt) { Text("Cancel") }
            },
        )
    }

    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text("What is a relay?") },
            text = {
                Text(
                    "A relay is a server that keeps posts and hands them out. Nostr has no central one: there " +
                        "are hundreds, run by different people, and you choose which ones this app uses.\n\n" +
                        "Reading from a relay means asking it for other people's posts. Posting to a relay " +
                        "means putting yours there, where anyone who reads that relay can find them.\n\n" +
                        "Most people read from several and post to two or three. If you are not sure, allow " +
                        "reading first — you can always allow posting later.",
                )
            },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text("Got it") } },
        )
    }

    opened?.let { url ->
        val row = all.firstOrNull { it.url == url }
        if (row == null) {
            opened = null
        } else {
            RelayDetailSheet(
                row = row,
                isConnected = url in connected,
                info = relayInfo[url],
                onDismiss = { opened = null },
                onSetPermissions = { read, write -> controller.relays.setPermissions(url, read, write) },
                onDeny = { controller.relays.deny(url) },
                onForget = { controller.relays.forget(url) },
                onFetchInfo = { controller.requestRelayInfo(url) },
                onToggleFavourite = { controller.relays.setFavourite(url, !row.isFavourite) },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = { Text("Relays", style = MaterialTheme.typography.titleLarge) },
            actions = {
                TextButton(onClick = { explaining = true }) { Text("What is a relay?") }
            },
        )
        // Outside the list on purpose. With hundreds of relays queued, a search
        // box that scrolled away with the content would be the one control the
        // user could never get back to.
        RelaySearchBar(query = query, onQueryChange = { query = it })
        RelayFilterChips(state = state, selected = filter, onSelect = { filter = it })
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (shown.isEmpty()) {
                item {
                    NoRelayMatch(
                        query = query,
                        anyRelaysAtAll = all.isNotEmpty(),
                        onAdd = {
                            controller.relays.add(query.trim(), read = true, write = false)
                            query = ""
                        },
                    )
                }
            }

            items(shown, key = { it.url.url }) { row ->
                RelayRow(
                    row = row,
                    isConnected = row.url in connected,
                    onOpen = { opened = row.url },
                    onToggleFavourite = { controller.relays.setFavourite(row.url, !row.isFavourite) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item { RelayScreenFooter() }
        }
    }
}

// ---- the row model ------------------------------------------------------

/** The three states a relay can be in, as one list rather than three. */
private enum class RelayStatus { Allowed, Waiting, Blocked }

private data class RelayRowItem(
    val url: RelayUrl,
    val status: RelayStatus,
    val publishers: Int,
    val isFavourite: Boolean = false,
    val grant: RelayGrant? = null,
    val reasons: Set<DiscoveryReason> = emptySet(),
)

/**
 * Flattens the three lists into one, keeping the controller's ordering.
 *
 * Allowed first, then waiting, then blocked: within each the controller has
 * already sorted busiest-first, which is the ordering that matters once the
 * queue is long.
 */
private fun RelayScreenState.rows(): List<RelayRowItem> =
    buildList {
        approved.forEach {
            add(RelayRowItem(it.url, RelayStatus.Allowed, publishersAt(it.url), isFavourite(it.url), grant = it))
        }
        pending.forEach {
            add(RelayRowItem(it.url, RelayStatus.Waiting, publishersAt(it.url), isFavourite(it.url), reasons = it.reasons))
        }
        denied.forEach { add(RelayRowItem(it, RelayStatus.Blocked, publishersAt(it), isFavourite(it))) }
    }

private enum class RelayFilter(
    val label: String,
) {
    All("All"),
    Allowed("Allowed"),
    Waiting("Waiting"),
    Blocked("Blocked"),
    ;

    fun accepts(status: RelayStatus): Boolean =
        when (this) {
            All -> true
            Allowed -> status == RelayStatus.Allowed
            Waiting -> status == RelayStatus.Waiting
            Blocked -> status == RelayStatus.Blocked
        }
}

// ---- header -------------------------------------------------------------

@Composable
private fun RelaySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search relays") },
        singleLine = true,
        leadingIcon = { Icon(WayfarerIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(WayfarerIcons.Close, contentDescription = "Clear the search")
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RelayFilterChips(
    state: RelayScreenState,
    selected: RelayFilter,
    onSelect: (RelayFilter) -> Unit,
) {
    val counts =
        mapOf(
            RelayFilter.All to state.approved.size + state.pending.size + state.denied.size,
            RelayFilter.Allowed to state.approved.size,
            RelayFilter.Waiting to state.pending.size,
            RelayFilter.Blocked to state.denied.size,
        )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        for (option in RelayFilter.entries) {
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text("${option.label} (${counts[option] ?: 0})") },
            )
        }
    }
}

// ---- rows ---------------------------------------------------------------

/**
 * One relay, at a height that never varies.
 *
 * Two lines and a fixed 72dp: whatever a relay has to say about itself, it says
 * it in the sheet this row opens, so a list of six and a list of six hundred
 * scroll the same way.
 */
@Composable
private fun RelayRow(
    row: RelayRowItem,
    isConnected: Boolean,
    onOpen: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.url.display(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.summary(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isConnected) ConnectedDot()
        FavouriteButton(row.isFavourite, onToggleFavourite)
    }
}

/**
 * The one number worth ranking by: how many people publish here.
 *
 * Framed as people rather than relays because that is the decision being made —
 * allowing this relay is what makes those authors' posts reachable at all.
 */
private fun RelayRowItem.summary(): String {
    val state =
        when (status) {
            RelayStatus.Allowed ->
                when {
                    grant?.read == true && grant.write -> "allowed to read and post"
                    grant?.write == true -> "allowed to post"
                    else -> "allowed to read"
                }
            RelayStatus.Waiting -> "waiting for you"
            RelayStatus.Blocked -> "blocked"
        }
    return when (publishers) {
        0 -> state
        1 -> "1 person publishes here · $state"
        else -> "$publishers people publish here · $state"
    }
}

@Composable
private fun ConnectedDot() {
    Box(
        Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@Composable
private fun NoRelayMatch(
    query: String,
    anyRelaysAtAll: Boolean,
    onAdd: () -> Unit,
) {
    val trimmed = query.trim()
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (trimmed.isEmpty()) {
            Text(
                if (anyRelaysAtAll) {
                    "Nothing in this category."
                } else {
                    "No relays yet. Wayfarer is connected to nothing until you allow one."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        Text("\"$trimmed\" is not in your list at all.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "It has not been allowed, blocked, or suggested to you. Adding it lets Wayfarer read from it; " +
                "posting stays off until you say otherwise.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onAdd) { Text("Add it, for reading") }
    }
}

// ---- the detail sheet ---------------------------------------------------

/**
 * Everything a relay has to say, moved off the row.
 *
 * The permission decision lives here too, so the same surface carries the reason
 * the app wants this relay, what the relay says about itself, and the answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayDetailSheet(
    row: RelayRowItem,
    isConnected: Boolean,
    info: RelayInfoService.Entry?,
    onDismiss: () -> Unit,
    onSetPermissions: (read: Boolean, write: Boolean) -> Unit,
    onDeny: () -> Unit,
    onForget: () -> Unit,
    onFetchInfo: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            // Scrollable, and the actions come first. A relay named by fifty
            // follows carries fifty discovery reasons, and with the buttons
            // below them in an unscrollable column they rendered past the bottom
            // edge — unreachable in exactly the case where the decision mattered
            // most.
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.url.display(),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f, fill = false),
                )
                FavouriteButton(row.isFavourite, onToggleFavourite)
            }
            Text(
                row.summary() + if (isConnected) " · connected now" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            when (row.status) {
                RelayStatus.Allowed -> {
                    val grant = row.grant
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = grant?.read == true,
                            onClick = { onSetPermissions(grant?.read != true, grant?.write == true) },
                            label = { Text("Get posts") },
                        )
                        FilterChip(
                            selected = grant?.write == true,
                            onClick = { onSetPermissions(grant?.read == true, grant?.write != true) },
                            label = { Text("Send my posts") },
                        )
                    }
                    Text(
                        permissionSummary(grant?.read == true, grant?.write == true),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            onForget()
                            onDismiss()
                        }) { Text("Remove") }
                        TextButton(onClick = {
                            onDeny()
                            onDismiss()
                        }) { Text("Block") }
                    }
                }

                RelayStatus.Waiting -> {
                    Text("Allow Wayfarer to get posts from this server?", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onSetPermissions(true, false)
                            onDismiss()
                        }) { Text("Get posts") }
                        OutlinedButton(onClick = {
                            onSetPermissions(true, true)
                            onDismiss()
                        }) { Text("And send mine") }
                        TextButton(onClick = {
                            onDeny()
                            onDismiss()
                        }) { Text("Block") }
                    }
                    Text(
                        "\"Get posts\" is the safe answer if you are unsure — it only reads. You can allow posting later.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                RelayStatus.Blocked -> {
                    Text(
                        "Wayfarer will not connect to this relay, and stops asking about it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = {
                        onForget()
                        onDismiss()
                    }) { Text("Unblock") }
                }
            }

            // Above the reasons, not below them. The reason list is unbounded —
            // one line per person whose relay list named this relay — so on a
            // relay many follows advertise, asking it to describe itself was
            // several screens down, which is the same thing that put the
            // allow and block buttons out of reach before they were moved up.
            HorizontalDivider()
            RelayInfoPanel(info, onFetchInfo)

            if (row.reasons.isNotEmpty()) {
                HorizontalDivider()
                Text("Why Wayfarer wants it", style = MaterialTheme.typography.titleSmall)
                for (reason in row.reasons) {
                    Text("· ${reason.describe()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The star. Filled when set, outlined when not, so the toggle does not jump. */
@Composable
private fun FavouriteButton(
    favourite: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            if (favourite) WayfarerIcons.Star else WayfarerIcons.StarOutline,
            contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
            tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- footer -------------------------------------------------------------

@Composable
private fun RelayScreenFooter() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        // No "add a relay" form here any more: typing an address into the search
        // field above offers to add it, which is the same act with one control
        // instead of two.
        Text(
            "Relays are the servers that hold nostr posts. Wayfarer connects to the ones you allow " +
                "here, and to nothing else — not on startup, not in the background.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "This list lives on this phone. Changing it publishes nothing and tells nobody.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * What a grant means, said in terms of what the server ends up knowing.
 *
 * Reading used to be described here as "nothing of yours is sent", which is
 * false in the way that matters. Asking a relay for posts means connecting to
 * it — it sees your IP address — and asking *by name*: the request carries the
 * public keys of the people whose posts are wanted, which is the account's
 * follow list, including the follows kept only on this phone. The relay-info
 * dialog on this same screen has always said the IP part plainly; there was no
 * reason for the standing relationship to say less than the one-off request.
 */
private fun permissionSummary(
    read: Boolean,
    write: Boolean,
): String =
    when {
        read && write ->
            "Wayfarer asks this server for posts and puts yours here too. It sees your IP address, and " +
                "which accounts you are reading — that is how it knows what to send back."
        read ->
            "Wayfarer asks this server for posts. Nothing of yours is published here, but it does see your " +
                "IP address and which accounts you are reading, including anyone followed only on this phone."
        write -> "Your posts are sent here, but nothing is read back."
        else -> "Nothing is allowed, so this relay is not used at all."
    }

/**
 * NIP-11 self-description, shown where the allow/block decision is made.
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
        null -> TextButton(onClick = onFetch) { Text("Ask this relay to describe itself") }
        RelayInfoService.Entry.Loading -> Text("Asking…", style = MaterialTheme.typography.bodySmall)
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
                        "needs a login this app does not support".takeIf { info.info.authRequired },
                        "charges for posting".takeIf { info.info.paymentRequired },
                    )
                if (flags.isNotEmpty()) {
                    Text(flags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                }
                info.info.postingPolicy?.let { Text("Posting policy: $it", style = MaterialTheme.typography.labelSmall) }
            }
    }
}

/**
 * Why this relay is being asked about, as a sentence.
 *
 * The detail carries the specific — which person, which post — and the source
 * says what kind of claim it is. Both matter: "somebody publishes here" is a
 * different decision from "a stranger's post mentioned it", and the old wording
 * blurred them into fragments that read the same.
 */
private fun DiscoveryReason.describe(): String {
    val detail = detail?.takeIf { it.isNotBlank() }
    return when (source) {
        DiscoverySource.BOOTSTRAP -> "Wayfarer ships with it as a starting point"
        DiscoverySource.USER_ENTERED -> detail?.let { "You asked for it — $it" } ?: "You asked for it"
        DiscoverySource.OWN_RELAY_LIST -> detail?.let { "Your own relay list says $it" } ?: "It is on your own relay list"
        DiscoverySource.AUTHOR_RELAY_LIST ->
            detail?.let { "$it, and you have read their posts" }
                ?: "Somebody whose posts you read publishes here"
        DiscoverySource.EVENT_HINT -> detail?.replaceFirstChar { it.uppercase() } ?: "Something you opened pointed at it"
    }
}
