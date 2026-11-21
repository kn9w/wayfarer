package app.wayfarer.android.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.PostPictures
import app.wayfarer.android.ui.icons.WayfarerIcons
import app.wayfarer.android.ui.rememberCopyToClipboard
import app.wayfarer.android.ui.theme.localAccent
import app.wayfarer.android.ui.theme.publicAccent
import app.wayfarer.android.ui.theme.publicButtonColors
import app.wayfarer.android.ui.theme.publicOutlinedButtonColors
import app.wayfarer.android.viewmodel.ActivityFilter
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.BrowseMode
import app.wayfarer.android.viewmodel.BrowseOrder
import app.wayfarer.android.viewmodel.FeedItem
import app.wayfarer.android.viewmodel.FeedState
import app.wayfarer.android.viewmodel.GlobalState
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.android.viewmodel.ThreadState
import app.wayfarer.android.viewmodel.rootRefOfNote
import app.wayfarer.core.model.EventId
import app.wayfarer.core.model.EventKind
import app.wayfarer.core.model.Note
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.model.ThreadRef
import app.wayfarer.core.repo.ThreadEntry
import app.wayfarer.core.repo.ThreadNode
import app.wayfarer.core.repo.threadTree

/** Horizontal inset shared by every post, so names and bodies line up down the feed. */
internal val PostHorizontalPadding = 16.dp

/**
 * The Global screen: two ways of reading, and nothing else.
 *
 * **Follows** steps through people. One person at a time, everything they have
 * written in one list, and the arrows move to the next person rather than the
 * next post — ordered by who wrote most recently, which is as close as this
 * shape gets to "what is new".
 *
 * **Relay** steps through posts. One relay, one post on the screen, arrows to
 * the next. A relay is a place you browse rather than a feed you catch up on,
 * and this is what that looks like.
 *
 * They are deliberately not the same list with a filter on it. Catching up with
 * somebody and looking at what a stranger's server is carrying are different
 * things to be doing, and flattening them would misrepresent both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(controller: AppController) {
    val global by controller.global.state.collectAsStateWithLifecycle()
    val refreshing by controller.refreshing.collectAsStateWithLifecycle()
    val activityWindow by controller.activityWindowDays.collectAsStateWithLifecycle()
    val feed by controller.feed.collectAsStateWithLifecycle()

    var filtering by remember { mutableStateOf(false) }

    if (filtering) {
        FilterSheet(
            state = global,
            windowDays = if (activityWindow == 1) "day" else "$activityWindow days",
            onDismiss = { filtering = false },
            onOrder = controller.global::setOrder,
            onActivity = controller.global::setActivity,
        )
    }

    Column(Modifier.fillMaxSize()) {
        GlobalHeader(global, controller, onFilter = { filtering = true })
        HorizontalDivider()
        ReadFootprint(feed, controller)

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { controller.refreshFeed() },
            state = rememberPullToRefreshState(),
            modifier = Modifier.weight(1f),
        ) {
            when (global.mode) {
                BrowseMode.Follows -> FollowsPane(global, controller)
                BrowseMode.Relay -> RelayPane(global, controller)
            }
        }

    }
}

/**
 * What the last load asked for, and of whom.
 *
 * The app has always been precise about publishing — a published note names
 * every relay that took it and what each one said — and silent about reading,
 * which is the direction that actually discloses something. A read names the
 * accounts it wants, so the relays asked are the relays told who you follow.
 * That was computed on every load and never shown; this is where it is shown.
 *
 * Collapsed to one line because it is context rather than content, and it must
 * not push the feed down the screen. Expanded, it names the relays: "four
 * relays" answers a much weaker question than "these four".
 */
@Composable
private fun ReadFootprint(
    feed: FeedState,
    controller: AppController,
) {
    if (!feed.loaded || feed.relaysQueried.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    val gaps =
        buildList {
            if (feed.unreachableAuthors.isNotEmpty()) add("${feed.unreachableAuthors.size} unreachable")
            if (feed.guessedAuthors.isNotEmpty()) add("${feed.guessedAuthors.size} guessed at")
        }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Asked ${feed.relaysQueried.size} " + (if (feed.relaysQueried.size == 1) "relay" else "relays") +
                if (gaps.isEmpty()) "" else " · " + gaps.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (expanded) {
            Text(
                "These servers were sent the public keys of the accounts this load wanted posts from.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (relay in feed.relaysQueried.sorted()) {
                Text(
                    relay.display(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (feed.unreachableAuthors.isNotEmpty()) {
                Text(
                    "Unreachable: " + feed.unreachableAuthors.joinToString(", ") { controller.displayName(it) } +
                        " — no relay you allow carries them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (feed.guessedAuthors.isNotEmpty()) {
                Text(
                    "Guessed at: " + feed.guessedAuthors.joinToString(", ") { controller.displayName(it) } +
                        " — they publish no relay list, so their posts were looked for on the relays you allow " +
                        "rather than where they actually post.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider()
}

// ---- header -------------------------------------------------------------

/**
 * Mode, subject and filter on one line.
 *
 * These were three stacked bars — the app header, a mode row, a subject row —
 * costing about 145dp before a single post, roughly a quarter of a phone. The
 * mode and what it is pointed at are one thought ("Follows · Alice"), so they
 * are one control; the position moved down to the paging bar, which was already
 * at the bottom and already about position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalHeader(
    global: GlobalState,
    controller: AppController,
    onFilter: () -> Unit,
) {
    var modeOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { modeOpen = true },
            ) {
                Text(
                    if (global.mode == BrowseMode.Follows) "Follows" else "Relay",
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(WayfarerIcons.DropDown, contentDescription = "Change what you are reading")
            }
            DropdownMenu(expanded = modeOpen, onDismissRequest = { modeOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Follows") },
                    onClick = {
                        modeOpen = false
                        controller.global.setMode(BrowseMode.Follows)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Relay") },
                    onClick = {
                        modeOpen = false
                        controller.global.setMode(BrowseMode.Relay)
                    },
                )
            }
        }

        Text(
            "·",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        // In Relay mode the subject is a place you choose; in Follows mode the
        // arrows are what move between people, so it is a label there.
        Subject(global, controller, onPick = { picking = true }, modifier = Modifier.weight(1f))

        IconButton(onClick = onFilter, modifier = Modifier.size(36.dp)) {
            Icon(WayfarerIcons.Funnel, contentDescription = "Filter and order", modifier = Modifier.size(20.dp))
        }
    }

    if (picking) {
        RelayPicker(global, controller, onDismiss = { picking = false })
    }
}

@Composable
private fun Subject(
    global: GlobalState,
    controller: AppController,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val relayMode = global.mode == BrowseMode.Relay
    val person = global.person
    val label =
        when {
            relayMode -> global.relay?.display() ?: "No relay allowed yet"
            person != null -> controller.displayName(person)
            else -> "Nobody to read"
        }

    // Relay mode picks a place; Follows mode names a person, and the name is the
    // obvious way to go and read who you are reading.
    val onTap: (() -> Unit)? =
        when {
            relayMode && global.relays.isNotEmpty() -> onPick
            !relayMode && person != null -> ({ controller.openProfile(person) })
            else -> null
        }

    Row(
        modifier = if (onTap != null) modifier.clickable(onClick = onTap) else modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = if (relayMode) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (relayMode && global.relays.isNotEmpty()) {
            Icon(WayfarerIcons.DropDown, contentDescription = "Choose a relay")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayPicker(
    global: GlobalState,
    controller: AppController,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                "Read from",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            for (url in global.relays) {
                Text(
                    url.display(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color =
                        // Trail: which relay you are reading is a choice from
                        // this phone's own list, and nothing about it is public.
                        if (url == global.relay) {
                            MaterialTheme.colorScheme.localAccent
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                controller.global.selectRelay(url)
                            }.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

// ---- the two panes ------------------------------------------------------

@Composable
private fun FollowsPane(
    global: GlobalState,
    controller: AppController,
) {
    val loadingMore by controller.loadingMore.collectAsStateWithLifecycle()
    val exhausted by controller.exhaustedAuthors.collectAsStateWithLifecycle()

    val person = global.person
    if (person == null) {
        EmptyPane {
            if (global.hiddenByActivity > 0) {
                Text("Nobody matches this filter", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${global.hiddenByActivity} of the people you follow are hidden by the activity filter. " +
                        "Widen it from the funnel above.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("You do not follow anyone yet", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Follows shows one person at a time, and there is nobody to show. Browse a relay instead, " +
                        "and open somebody's profile from a post you like.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { controller.global.setMode(BrowseMode.Relay) }) { Text("Browse a relay") }
            }
        }
        return
    }

    if (global.personPosts.isEmpty()) {
        EmptyPane {
            Text("Nothing from this person yet", style = MaterialTheme.typography.titleSmall)
            Text(
                "Wayfarer has not found anything they wrote on the relays it is allowed to read. They may " +
                    "publish somewhere you have not approved.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(global.personPosts, key = { it.key }) { item ->
            PostBody(item, controller)
            PostDivider()
        }

        // The same end as on a profile, and for the same reason: these are this
        // person's posts as far back as one query reached, not all of them.
        item {
            LoadMoreButton(
                loading = loadingMore,
                exhausted = person in exhausted,
                onLoadMore = { controller.loadMoreFrom(person) },
            )
        }
    }
}

/**
 * One post, filling the screen.
 *
 * Scrollable rather than clipped: a long note is still one post, and the arrows
 * are what move to the next one.
 */
@Composable
private fun RelayPane(
    global: GlobalState,
    controller: AppController,
) {
    if (global.relay == null) {
        EmptyPane {
            Text("No relay to read", style = MaterialTheme.typography.titleSmall)
            Text(
                "Wayfarer connects to nothing until you allow a relay. Allowing one is the whole of the setup.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { controller.go(Screen.Relays) }) { Text("Choose relays") }
        }
        return
    }

    val post = global.currentPost
    if (post == null) {
        EmptyPane {
            Text("Nothing from this relay yet", style = MaterialTheme.typography.titleSmall)
            Text(
                "${global.relay.display()} has not handed anything over. It may be quiet, or still connecting.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PostBody(post, controller)
    }
}

@Composable
private fun PostBody(
    item: FeedItem,
    controller: AppController,
) {
    when (item) {
        is FeedItem.Post ->
            NoteRow(note = item.note, controller = controller)
        is FeedItem.LongForm ->
            ArticleRow(
                article = item.article,
                controller = controller,
                onOpen = { controller.go(Screen.ReadArticle(item.article.address)) },
            )
    }
}

@Composable
private fun EmptyPane(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

// ---- paging -------------------------------------------------------------

/**
 * The arrows, above the navigation bar.
 *
 * They step through people in Follows and posts in Relay — the thing the mode is
 * organised around, which is why there is one control rather than two.
 */
@Composable
fun PagingBar(controller: AppController) {
    val global by controller.global.state.collectAsStateWithLifecycle()

    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagingArrow(
            icon = WayfarerIcons.ChevronLeft,
            description = if (global.mode == BrowseMode.Follows) "Previous person" else "Previous post",
            enabled = global.hasPrevious,
            onStep = { controller.global.previous() },
            onJump = { controller.global.first() },
        )
        Text(
            // The position moved down here from the subject row: this bar is
            // what steps through the set, so it is where "where am I" belongs.
            global.position.ifBlank {
                when (global.mode) {
                    BrowseMode.Follows -> "the people you follow"
                    BrowseMode.Relay -> "posts on this relay"
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PagingArrow(
            icon = WayfarerIcons.ChevronRight,
            description = if (global.mode == BrowseMode.Follows) "Next person" else "Next post",
            enabled = global.hasNext,
            onStep = { controller.global.next() },
            onJump = { controller.global.last() },
        )
    }
}

/**
 * One arrow: a tap steps, a long press goes all the way.
 *
 * Two hundred follows is otherwise two hundred taps from the far end, and the
 * rotation has no other way to get there.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagingArrow(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onStep: () -> Unit,
    onJump: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onStep,
                    onLongClick = onJump,
                    onLongClickLabel = "Jump to the end",
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint =
                if (enabled) {
                    LocalContentColor.current
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
        )
    }
}

// ---- filters ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    state: GlobalState,
    windowDays: String,
    onDismiss: () -> Unit,
    onOrder: (BrowseOrder) -> Unit,
    onActivity: (ActivityFilter) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Order", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.order == BrowseOrder.Chronological,
                    onClick = { onOrder(BrowseOrder.Chronological) },
                    label = { Text("Chronological") },
                )
                FilterChip(
                    selected = state.order == BrowseOrder.Random,
                    onClick = { onOrder(BrowseOrder.Random) },
                    label = { Text("Random") },
                )
            }
            Text(
                when (state.mode) {
                    BrowseMode.Follows ->
                        "Chronological puts whoever wrote most recently first. Random shuffles the order you " +
                            "meet them in, and holds that order until you pull to refresh."
                    BrowseMode.Relay ->
                        "Chronological is newest first. Random shuffles this relay's posts, and holds that " +
                            "order until you pull to refresh."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.mode == BrowseMode.Follows) {
                HorizontalDivider()
                Text("Activity", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.activity == ActivityFilter.Any,
                        onClick = { onActivity(ActivityFilter.Any) },
                        label = { Text("Everyone") },
                    )
                    FilterChip(
                        selected = state.activity == ActivityFilter.ActiveRecently,
                        onClick = { onActivity(ActivityFilter.ActiveRecently) },
                        label = { Text("Active") },
                    )
                    FilterChip(
                        selected = state.activity == ActivityFilter.QuietRecently,
                        onClick = { onActivity(ActivityFilter.QuietRecently) },
                        label = { Text("Quiet") },
                    )
                }
                Text(
                    "Active means they have posted in the last $windowDays; quiet means they have not. Judged " +
                        "only from what Wayfarer has actually fetched — somebody posting to relays you have not " +
                        "allowed will look quiet. The window is set in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.hiddenByActivity > 0) {
                    Text(
                        "${state.hiddenByActivity} of your follows are hidden by this filter.",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

// ---- posts --------------------------------------------------------------

/**
 * The conversation under a post, opened on demand.
 *
 * Closed, it is one quiet line. A count cannot be shown before the thread is
 * fetched — and fetching every thread in a feed would be a relay query per post,
 * the same mistake that stalled the streamed feed before profile lookups were
 * batched — so the affordance says "Replies" until it knows better and a number
 * afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadSection(
    root: ThreadRef,
    rootKind: String,
    rootAuthor: PubKey,
    controller: AppController,
    /**
     * Replaces the count wording when this section is a way into somebody else's
     * conversation rather than the replies to the post it sits under.
     */
    closedLabel: String? = null,
    /**
     * Whether to draw the post the conversation hangs from.
     *
     * True wherever this section is a way *into* a conversation rather than the
     * replies under a post already on screen. Without it, "see the conversation"
     * opened a list of answers with nothing being answered at the top of it —
     * every reply, and never the post — which is a thread starting in the
     * middle. False under a post, where drawing the root would be printing the
     * same note twice.
     */
    showRoot: Boolean = false,
) {
    val threads by controller.threads.threads.collectAsStateWithLifecycle()
    val expandedRoots by controller.threads.expanded.collectAsStateWithLifecycle()
    val collapsed by controller.threads.collapsed.collectAsStateWithLifecycle()
    val heldNotes by controller.allNotes.collectAsStateWithLifecycle()
    val fetchedRoots by controller.threadRoots.collectAsStateWithLifecycle()
    val state = threads[root] ?: ThreadState()
    val expanded = root in expandedRoots

    // Whichever store it is in, if it is in one at all. A root fetched with the
    // thread lands in threadRoots; one the feed already had is in allNotes.
    val rootId = (root as? ThreadRef.Event)?.id
    val rootNote = rootId?.let { heldNotes[it] ?: fetchedRoots[it] }

    // Null until somebody presses a Reply. Which one they pressed is the whole
    // of what the old composer could not say.
    var replying by remember(root) { mutableStateOf<ReplyTarget?>(null) }

    replying?.let { target ->
        ReplySheet(
            target = target,
            posting = state.posting,
            onDismiss = { replying = null },
            onSend = { text ->
                controller.threads.reply(
                    // The root is what the thread is about and never moves; the
                    // parent is what this reply answers. NIP-22 keeps both, so a
                    // reply to a reply can be refetched with its conversation
                    // and still sit under the right thing.
                    root = root,
                    rootKind = rootKind,
                    rootAuthor = rootAuthor,
                    parent = target.ref,
                    parentKind = target.kind,
                    parentAuthor = target.author,
                    content = text,
                )
                replying = null
            },
        )
    }

    // This used to return early on a loaded-and-empty thread, which took the
    // reply composer with it: open a note with no replies, close it again, and
    // there was no longer any way to reply to that note at all. An empty
    // conversation still needs one line, because that line is how you start one.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable { controller.threads.toggle(root) },
    ) {
        Icon(
            WayfarerIcons.Reply,
            contentDescription = null,
            // Moss: everything a reply leads to is published.
            tint = MaterialTheme.colorScheme.publicAccent,
            modifier = Modifier.size(14.dp),
        )
        Text(
            when {
                state.loading -> "Loading…"
                closedLabel != null -> closedLabel
                !state.loaded -> "Replies"
                state.entries.isEmpty() -> "Reply"
                state.entries.size == 1 -> "1 reply"
                else -> "${state.entries.size} replies"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.publicAccent,
        )
    }

    if (!expanded) return

    val nodes = threadTree(state.entries, (root as? ThreadRef.Event)?.id, collapsed)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showRoot) ThreadRootRow(rootId, rootNote, state.loading, controller)

        if (state.loaded && state.entries.isEmpty()) {
            Text(
                "Nothing here yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (node in nodes) {
            ThreadEntryRow(
                node = node,
                collapsed = node.entry.id in collapsed,
                controller = controller,
                onReply = { entry ->
                    replying =
                        ReplyTarget(
                            ref = ThreadRef.Event(entry.id),
                            kind = if (entry.isComment) EventKind.COMMENT.toString() else EventKind.TEXT_NOTE.toString(),
                            author = entry.author,
                            toName = controller.displayName(entry.author),
                            quote = entry.content,
                        )
                },
            )
        }
    }

    // The way to answer the post itself, said as plainly as the per-reply ones.
    OutlinedButton(
        onClick = {
            replying =
                ReplyTarget(
                    ref = root,
                    kind = rootKind,
                    author = rootAuthor,
                    toName = controller.displayName(rootAuthor),
                    quote = rootNote?.content.orEmpty(),
                )
        },
        colors = publicOutlinedButtonColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) { Text("Reply to ${controller.displayName(rootAuthor)}") }
}

/**
 * The post a conversation is about, at the top of it.
 *
 * The thing "see the conversation" was missing. A thread is fetched by asking
 * for everything that *points at* a root, which is every reply and never the
 * root itself, so a conversation opened from one of its replies rendered as a
 * list of answers to nothing. `ThreadRepository.load` now asks for the root
 * event by id as well, and this is where it lands.
 *
 * It can still be absent — a relay is free to hand over a reply and not the post
 * it answers, and this app will not go hunting further — so the missing case
 * says which post is missing rather than leaving a silent gap where it was.
 */
@Composable
private fun ThreadRootRow(
    rootId: EventId?,
    root: Note?,
    loading: Boolean,
    controller: AppController,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "The post this answers",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (root == null) {
            Text(
                if (loading) {
                    "Looking for it…"
                } else {
                    "Not on the relays you allow, so the conversation starts at the first reply below."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PostByline(
                    author = root.author,
                    createdAt = root.createdAt,
                    controller = controller,
                    onOpenAuthor = { controller.openProfile(root.author) },
                )
                Text(
                    root.content,
                    style = MaterialTheme.typography.bodyMedium,
                    // The whole post opens on its own screen, where it is the
                    // subject rather than the header of somebody else's reply.
                    modifier =
                        rootId?.let { id ->
                            Modifier.fillMaxWidth().clickable { controller.go(Screen.ReadNote(id)) }
                        } ?: Modifier,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * One reply, at the depth it was written at.
 *
 * Indentation is uncapped: a conversation's shape is the point, and truncating
 * it at some arbitrary level puts two different structures on screen looking
 * identical. Long-pressing a reply that has any of its own folds them away,
 * which is what keeps a deep thread readable instead of a depth limit.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadEntryRow(
    node: ThreadNode,
    collapsed: Boolean,
    controller: AppController,
    onReply: (ThreadEntry) -> Unit,
) {
    val entry = node.entry
    val foldable = node.descendants > 0

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = (12 + node.depth * 12).dp)
                .then(
                    if (foldable) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { controller.threads.toggleCollapsed(entry.id) },
                        )
                    } else {
                        Modifier
                    },
                ).padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PostByline(
            author = entry.author,
            createdAt = entry.createdAt,
            controller = controller,
            onOpenAuthor = { controller.openProfile(entry.author) },
        )
        Text(entry.content, style = MaterialTheme.typography.bodySmall)

        // Only when something is actually hidden. An instruction under every
        // foldable reply was noise on the far more common case where nothing is.
        if (collapsed && node.descendants > 0) {
            Text(
                if (node.descendants == 1) "1 reply hidden" else "${node.descendants} replies hidden",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Answering *this* reply, which the one composer at the foot of the
        // thread could never do — it always replied to the root.
        Text(
            "Reply",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.publicAccent,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onReply(entry) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/**
 * What a reply is aimed at.
 *
 * [ref], [kind] and [author] are what the event will carry; [toName] and [quote]
 * are what the person writing it sees. Both halves travel together because the
 * bug this exists to fix was them coming apart: the composer was one unlabelled
 * field at the foot of a thread that always answered the *root*, whichever reply
 * you had just read, so what you were about to answer was something you had to
 * infer from a box that said nothing.
 */
internal data class ReplyTarget(
    val ref: ThreadRef,
    val kind: String,
    val author: PubKey?,
    val toName: String,
    val quote: String,
)

/**
 * Writing a reply, with the thing being replied to at the top of it.
 *
 * A sheet rather than an inline field: it names its target, quotes it, and has
 * room to write in — and, being modal, it cannot be left ambiguous by scrolling
 * away from whatever it was under.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplySheet(
    target: ReplyTarget,
    posting: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember(target.ref, target.quote) { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                // The field is the point of the sheet, so it stays above the
                // keyboard rather than under it.
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Replying to ${target.toName}", style = MaterialTheme.typography.titleMedium)

            // Four lines of what is being answered. Not the whole of it: this
            // is here to say which post, and a long one would push the field
            // somebody opened the sheet to type in off the screen.
            if (target.quote.isNotBlank()) {
                Text(
                    target.quote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(10.dp),
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Your reply") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSend(draft) },
                    enabled = !posting && draft.isNotBlank(),
                    colors = publicButtonColors(),
                ) { Text(if (posting) "Sending…" else "Send") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }

            Text(
                "A reply is a signed public note. It goes to your write relays and to " +
                    "${target.toName}'s read relays, so it reaches them.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one pixel that separates two posts. */
@Composable
internal fun PostDivider() {
    HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun NoteRow(
    note: Note,
    controller: AppController,
    /**
     * Opens the note on its own screen. Given only where a note sits in a list
     * of many; on the note's own screen there is nowhere further to go.
     */
    onOpen: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PostHorizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PostByline(
            author = note.author,
            createdAt = note.createdAt,
            controller = controller,
            onOpenAuthor = { controller.openProfile(note.author) },
            trailing = { EventMenu(note.id, controller) },
        )

        // A reply used to render as though it were a post of its own, because
        // Note.replyTo was parsed and then never read by anything. Saying what it
        // answers is half of placing it; the other half is being able to go and
        // read the rest, so this line opens the conversation it belongs to.
        //
        // Rooted at threadRoot, not at the parent: the reader wants the whole
        // exchange, and under NIP-10 the root is what the other replies name.
        note.replyTo?.let { parent ->
            val conversation = note.threadRoot ?: parent
            ThreadSection(
                root = rootRefOfNote(conversation),
                rootKind = "1",
                rootAuthor = controller.authorOf(conversation) ?: note.author,
                controller = controller,
                closedLabel = controller.replyContextFor(parent) + " · see the conversation",
                // This is a way into somebody else's conversation, so it opens
                // with the post that conversation is about.
                showRoot = true,
            )
        }

        // The body opens the note, rather than the whole row: the reply controls
        // and the menu below have taps of their own that must keep working.
        Text(
            note.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = if (onOpen == null) Modifier else Modifier.fillMaxWidth().clickable(onClick = onOpen),
        )

        // Nostr has no attachment: a picture in a note is a URL in the text
        // above. Its host is already in the waiting list — every post the app
        // takes in records the servers it points at — so this either shows the
        // picture, for a host the reader has allowed, or names the server and
        // leads to the decision.
        PostPictures(
            content = note.content,
            controller = controller,
            onOpenMedia = { host ->
                controller.openMediaHost(host, "a picture in a post by ${controller.displayName(note.author)}")
            },
        )

        // Provenance: which relay actually delivered this note. Under the
        // outbox model that is a meaningful thing to be able to see.
        Text(
            "seen on " + note.seenOn.joinToString(", ") { it.display() }.ifBlank { "this device" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )

        ThreadSection(root = rootRefOfNote(note.id), rootKind = "1", rootAuthor = note.author, controller = controller)
    }
}

/**
 * Who wrote it and when — the two facts every post carries.
 *
 * A name and nothing else. There was a face here, and every one of them was a
 * request to a server chosen by somebody else, repeated per row: forty little
 * circles down a feed, forty fetches, and forty prompts about hosts for a reader
 * who came to read the words. Faces and banners now live on the one screen that
 * is actually about a person — their profile — and a byline says what it is for,
 * which is who wrote this. Where no name is known that is their npub, shortened.
 */
@Composable
internal fun PostByline(
    // The key rather than the name: every caller was passing
    // controller.displayName(author), and the key is what opens the profile.
    author: PubKey,
    createdAt: Long,
    controller: AppController,
    onOpenAuthor: (() -> Unit)? = null,
    /** Sits after the timestamp. The overflow menu, where a post has one. */
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            controller.displayName(author),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                if (onOpenAuthor == null) {
                    Modifier.weight(1f)
                } else {
                    Modifier.weight(1f).clickable(onClick = onOpenAuthor)
                },
        )
        Text(
            controller.timeAgo(createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}

/**
 * What can be done with one event.
 *
 * All three answers need the event itself rather than the note or article built
 * from it — a projection has no signature, no tags and no id to send — which is
 * why the repositories keep what they absorbed.
 */
@Composable
internal fun EventMenu(
    id: EventId,
    controller: AppController,
) {
    var open by remember { mutableStateOf(false) }
    var showingJson by remember { mutableStateOf(false) }
    var rebroadcasting by remember { mutableStateOf(false) }
    val copy = rememberCopyToClipboard()

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                WayfarerIcons.More,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Copy event id") },
                onClick = {
                    // As `nostr:note1…` rather than as bare hex: that is the
                    // form NIP-21 defines and the form another client — or a
                    // post written here — can actually resolve. The hex is
                    // still one item down, in the raw JSON.
                    copy(controller.shareableEventId(id))
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("View raw JSON") },
                onClick = {
                    showingJson = true
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Rebroadcast") },
                onClick = {
                    rebroadcasting = true
                    open = false
                },
            )
        }
    }

    if (showingJson) {
        RawJsonDialog(
            json = controller.rawJsonOf(id),
            onCopy = copy,
            onDismiss = { showingJson = false },
        )
    }

    if (rebroadcasting) {
        RebroadcastDialog(
            controller = controller,
            onSend = { relays ->
                controller.rebroadcast(id, relays)
                rebroadcasting = false
            },
            onDismiss = { rebroadcasting = false },
        )
    }
}

@Composable
private fun RawJsonDialog(
    json: String?,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw event") },
        text = {
            if (json == null) {
                Text("This post's event is no longer held, so there is nothing to show.")
            } else {
                Text(
                    json,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            if (json != null) {
                TextButton(onClick = {
                    onCopy(json)
                    onDismiss()
                }) { Text("Copy") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Where to send it again.
 *
 * Write-approved relays only: a relay approved for reading was approved for
 * reading, and sending to it under the heading of "approved" would be putting
 * the user's posts somewhere they did not agree to.
 */
@Composable
private fun RebroadcastDialog(
    controller: AppController,
    onSend: (Set<RelayUrl>) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = remember { controller.rebroadcastTargets() }
    val chosen = remember { mutableStateListOf<RelayUrl>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send this post again") },
        text = {
            if (targets.isEmpty()) {
                Text(
                    "No relay is approved for sending. Approve one for \"Send my posts\" in Relays first — " +
                        "a relay you allowed only for getting posts is not one you agreed to publish to.",
                )
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "The same event, unchanged, offered to somewhere else that will carry it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (relay in targets) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (relay in chosen) chosen.remove(relay) else chosen.add(relay)
                                    }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = relay in chosen,
                                onCheckedChange = {
                                    if (relay in chosen) chosen.remove(relay) else chosen.add(relay)
                                },
                            )
                            Text(relay.display(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (targets.isNotEmpty()) {
                Button(onClick = { onSend(chosen.toSet()) }, enabled = chosen.isNotEmpty()) { Text("Send") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
