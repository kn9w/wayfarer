package app.wayfarer.android.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.wayfarer.android.ui.theme.localButtonColors
import app.wayfarer.android.ui.theme.localOutlinedButtonColors
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.MediaScreenState
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.MediaReason
import app.wayfarer.core.model.MediaSource

/**
 * Which servers may be asked for a picture.
 *
 * The relay screen's twin, deliberately: the same fixed-height rows, the same
 * search box outside the list, the same filter chips, and everything variable in
 * the sheet a row opens. That is not imitation for its own sake — a user who has
 * learned to answer "may this app talk to nos.lol?" should not have to learn a
 * second interface to answer "may it fetch a picture from image.nostr.build?".
 *
 * The wording carries the same weight it does there. Nobody has an opinion about
 * a "media host", so every control says what it does — show pictures from this
 * server, never load from here — and the explainer says what a request costs
 * before the user grants one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(controller: AppController) {
    val state by controller.media.state.collectAsStateWithLifecycle()
    var explaining by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(MediaFilter.All) }
    var opened by remember { mutableStateOf<MediaHost?>(null) }

    // Something elsewhere — the badge on an avatar that could not be drawn —
    // asked for this host. Consumed rather than observed, so going back and
    // forward again does not re-open the sheet by itself.
    val focus by controller.mediaFocus.collectAsStateWithLifecycle()
    LaunchedEffect(focus) {
        focus?.let {
            opened = it
            controller.clearMediaFocus()
        }
    }

    val all = remember(state) { state.rows() }
    val shown =
        remember(all, query, filter) {
            all.filter { filter.accepts(it.status) && it.host.display().contains(query.trim(), ignoreCase = true) }
        }

    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text("What is a media server?") },
            text = {
                Text(
                    "A media server is wherever somebody's picture is kept. Nostr does not store pictures: a " +
                        "profile only names a web address, and that address can be any server at all.\n\n" +
                        "Loading a picture means asking that server for it. The server learns your IP address " +
                        "and which profiles you are looking at — which is more than a relay you never approved " +
                        "gets to know. So Wayfarer asks nothing of a server that is not on this list.\n\n" +
                        "Nothing here is published. Allowing a server changes what this phone draws and tells " +
                        "nobody, including the server.",
                )
            },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text("Got it") } },
        )
    }

    opened?.let { host ->
        val row = all.firstOrNull { it.host == host }
        if (row == null) {
            opened = null
        } else {
            MediaDetailSheet(
                row = row,
                onDismiss = { opened = null },
                onAllow = { controller.media.allow(host) },
                onRevoke = { controller.media.revoke(host) },
                onDeny = { controller.media.deny(host) },
                onForget = { controller.media.forget(host) },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = { Text("Pictures", style = MaterialTheme.typography.titleLarge) },
            actions = {
                TextButton(onClick = { explaining = true }) { Text("What is a media server?") }
            },
        )
        MediaSearchBar(query = query, onQueryChange = { query = it })
        MediaFilterChips(state = state, selected = filter, onSelect = { filter = it })
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (shown.isEmpty()) {
                item {
                    NoMediaMatch(
                        query = query,
                        anyHostsAtAll = all.isNotEmpty(),
                        onAdd = {
                            controller.media.add(query.trim())
                            query = ""
                        },
                    )
                }
            }

            items(shown, key = { it.host.host }) { row ->
                MediaRow(row = row, onOpen = { opened = row.host })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item { MediaScreenFooter() }
        }
    }
}

// ---- the row model ------------------------------------------------------

/** The three states a media host can be in, as one list rather than three. */
private enum class MediaStatus { Allowed, Waiting, Blocked }

private data class MediaRowItem(
    val host: MediaHost,
    val status: MediaStatus,
    val users: Int,
    val reasons: Set<MediaReason> = emptySet(),
)

/** Flattens the three lists into one, keeping the controller's ordering. */
private fun MediaScreenState.rows(): List<MediaRowItem> =
    buildList {
        approved.forEach { add(MediaRowItem(it.host, MediaStatus.Allowed, usersAt(it.host))) }
        pending.forEach { add(MediaRowItem(it.host, MediaStatus.Waiting, usersAt(it.host), it.reasons)) }
        denied.forEach { add(MediaRowItem(it, MediaStatus.Blocked, usersAt(it))) }
    }

private enum class MediaFilter(
    val label: String,
) {
    All("All"),
    Allowed("Allowed"),
    Waiting("Waiting"),
    Blocked("Blocked"),
    ;

    fun accepts(status: MediaStatus): Boolean =
        when (this) {
            All -> true
            Allowed -> status == MediaStatus.Allowed
            Waiting -> status == MediaStatus.Waiting
            Blocked -> status == MediaStatus.Blocked
        }
}

// ---- header -------------------------------------------------------------

@Composable
private fun MediaSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search servers") },
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
private fun MediaFilterChips(
    state: MediaScreenState,
    selected: MediaFilter,
    onSelect: (MediaFilter) -> Unit,
) {
    val counts =
        mapOf(
            MediaFilter.All to state.approved.size + state.pending.size + state.denied.size,
            MediaFilter.Allowed to state.approved.size,
            MediaFilter.Waiting to state.pending.size,
            MediaFilter.Blocked to state.denied.size,
        )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        for (option in MediaFilter.entries) {
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text("${option.label} (${counts[option] ?: 0})") },
            )
        }
    }
}

// ---- rows ---------------------------------------------------------------

/** One server, at a height that never varies. See `RelayRow` for why. */
@Composable
private fun MediaRow(
    row: MediaRowItem,
    onOpen: () -> Unit,
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
                row.host.display(),
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
    }
}

/**
 * The one number worth ranking by: how many of the people this app has seen keep
 * a picture here.
 *
 * Framed as people rather than pictures because that is the decision being made —
 * allowing this server is what puts faces on those profiles.
 */
private fun MediaRowItem.summary(): String {
    val state =
        when (status) {
            MediaStatus.Allowed -> "pictures allowed"
            MediaStatus.Waiting -> "waiting for you"
            MediaStatus.Blocked -> "never loaded"
        }
    return when (users) {
        0 -> state
        1 -> "1 person keeps pictures here · $state"
        else -> "$users people keep pictures here · $state"
    }
}

@Composable
private fun NoMediaMatch(
    query: String,
    anyHostsAtAll: Boolean,
    onAdd: () -> Unit,
) {
    val trimmed = query.trim()
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            trimmed.isNotEmpty() -> {
                Text("No server here matches “$trimmed”.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onAdd, colors = localOutlinedButtonColors()) { Text("Add $trimmed to the list") }
                Text(
                    "Adding it only puts it in front of you to decide about. Nothing is loaded until you allow it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            anyHostsAtAll ->
                Text("Nothing in this filter.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text("No pictures have been asked for yet.", style = MaterialTheme.typography.titleSmall)
                Text(
                    "When somebody you read has a profile picture, the server it lives on appears here for you " +
                        "to decide about. Until then every face in the app is a mark drawn from the person's own " +
                        "key, and nothing has been fetched from anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---- the sheet ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaDetailSheet(
    row: MediaRowItem,
    onDismiss: () -> Unit,
    onAllow: () -> Unit,
    onRevoke: () -> Unit,
    onDeny: () -> Unit,
    onForget: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            // Scrollable with the actions first, for the same reason the relay
            // sheet is: the reason list below is unbounded.
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                row.host.display(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(row.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Said here, next to the button that grants it, rather than only in
            // the explainer somebody may never open.
            Text(
                "Allowing this means asking ${row.host.display()} for pictures. It will see your IP address and " +
                    "which pictures you asked for. It is not allowed anything else — nothing is uploaded and no " +
                    "post is sent to it.",
                style = MaterialTheme.typography.bodySmall,
            )

            // Trail throughout, like the relay screen: this list lives on this
            // phone, and answering any of these publishes nothing.
            when (row.status) {
                MediaStatus.Allowed -> {
                    OutlinedButton(
                        onClick = {
                            onRevoke()
                            onDismiss()
                        },
                        colors = localOutlinedButtonColors(),
                    ) { Text("Stop showing pictures from here") }
                }
                MediaStatus.Waiting -> {
                    Button(
                        onClick = {
                            onAllow()
                            onDismiss()
                        },
                        colors = localButtonColors(),
                    ) { Text("Show pictures from this server") }
                    OutlinedButton(
                        onClick = {
                            onDeny()
                            onDismiss()
                        },
                        colors = localOutlinedButtonColors(),
                    ) { Text("Never load from here") }
                }
                MediaStatus.Blocked -> {
                    OutlinedButton(
                        onClick = {
                            onForget()
                            onDismiss()
                        },
                        colors = localOutlinedButtonColors(),
                    ) { Text("Unblock") }
                }
            }

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

// ---- footer -------------------------------------------------------------

@Composable
private fun MediaScreenFooter() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Wayfarer fetches pictures only from the servers you allow here, and from nothing else — not on " +
                "startup, not in the background.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "This is not the relay list. It decides where pictures come from, not where posts do, and like the " +
                "relay list it lives on this phone: changing it publishes nothing and tells nobody.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun MediaReason.describe(): String {
    val what =
        when (source) {
            MediaSource.AVATAR -> "somebody's profile picture"
            MediaSource.BANNER -> "a profile banner"
            MediaSource.ARTICLE_IMAGE -> "an article's header image"
            MediaSource.POST_IMAGE -> "a picture in a post"
            MediaSource.USER_ENTERED -> "you added it here"
        }
    return detail?.let { "$what — $it" } ?: what
}
