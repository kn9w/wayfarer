package app.wayfarer.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.icons.WayfarerIcons
import app.wayfarer.android.ui.theme.localAccent
import app.wayfarer.android.ui.theme.onPublicAccent
import app.wayfarer.android.ui.theme.publicAccent
import app.wayfarer.android.ui.screen.ComposeNoteScreen
import app.wayfarer.android.ui.screen.EditArticleScreen
import app.wayfarer.android.ui.screen.EditProfileScreen
import app.wayfarer.android.ui.screen.FollowsScreen
import app.wayfarer.android.ui.screen.HomeScreen
import app.wayfarer.android.ui.screen.MediaScreen
import app.wayfarer.android.ui.screen.OnboardingSurface
import app.wayfarer.android.ui.screen.PagingBar
import app.wayfarer.android.ui.screen.ProfileScreen
import app.wayfarer.android.ui.screen.ReadArticleScreen
import app.wayfarer.android.ui.screen.ReadNoteScreen
import app.wayfarer.android.ui.screen.RelayListScreen
import app.wayfarer.android.ui.screen.RelayScreen
import app.wayfarer.android.ui.screen.SettingsScreen
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.DeviceAuthOutcome
import app.wayfarer.android.viewmodel.ExternalSignerIdentity
import app.wayfarer.android.viewmodel.OnboardingStep
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.core.Wayfarer
import app.wayfarer.core.repo.HeaderStyle
import kotlinx.coroutines.CoroutineScope

/**
 * The whole UI tree.
 *
 * Navigation is a single state value rather than a navigation library: this app
 * has a handful of screens and no deep links, and the dependency would outweigh
 * the `when` block below.
 *
 * Two surfaces, not one. Onboarding owns the entire window while it is up, with
 * no scaffold around it; everything else lives inside the tab bar. That boundary
 * is load-bearing — the one-time key backup is an onboarding step, and drawing a
 * navigation bar around it is how a user loses their key to a mis-tap.
 *
 * Three tabs, not five. Writing is an action, not a place: it took two of the
 * five slots while being the one thing a reader may never do, so it moved to the
 * button that composes — leaving the bar to say only where you are, which is
 * here (Local) or everywhere (Global), plus the relays that connect the two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WayfarerApp(
    core: Wayfarer,
    scope: CoroutineScope,
    /**
     * Asks a NIP-55 signer app who the user is, or null when none is installed.
     *
     * Supplied by the activity rather than discovered here: probing the package
     * manager and launching intents is platform work, and keeping it out of this
     * file is what lets the whole UI tree be compiled and checked off-device.
     */
    externalSignerLogin: (suspend () -> ExternalSignerIdentity?)? = null,
    /** Confirms the device owner before the secret key is shown. Same reasoning. */
    deviceAuth: (suspend () -> DeviceAuthOutcome)? = null,
    /** Scans a QR code with the camera, or null when this device cannot. */
    qrScan: (suspend () -> String?)? = null,
    /** What the installed NIP-55 signer calls itself, or null when there is none. */
    externalSignerName: String? = null,
) {
    // Held through rememberUpdatedState and read at call time. The activity passes
    // a capturing lambda, so a fresh instance arrives on every recomposition; using
    // it as a `remember` key rebuilt the controller each frame, which re-ran startup
    // and reset `screenState` to Home so no navigation could ever stick.
    val currentSignerLogin = rememberUpdatedState(externalSignerLogin)
    val currentDeviceAuth = rememberUpdatedState(deviceAuth)
    val currentQrScan = rememberUpdatedState(qrScan)
    val currentSignerName = rememberUpdatedState(externalSignerName)
    // Taken from the composition local the activity already provides, so this
    // needs no new parameter and cannot go stale against the loader in use.
    val images = LocalImageLoader.current
    val currentImages = rememberUpdatedState(images)
    val controller =
        remember(core) {
            AppController(
                core = core,
                scope = scope,
                externalSignerLogin = { currentSignerLogin.value },
                deviceAuth = { currentDeviceAuth.value },
                qrScan = { currentQrScan.value },
                externalSignerName = { currentSignerName.value },
                clearImageCache = { currentImages.value?.let { loader -> ({ loader.clear() }) } },
            )
        }

    // Relay sockets follow the foreground. They are held open the whole time the
    // app is on screen — which is what makes the connected count mean something
    // and lets posts arrive without a refresh — and dropped when it is not, so
    // the app is not quietly talking to relays while nobody is looking.
    //
    // Declared above the onboarding branch on purpose: the observer has to be
    // registered in both states, or leaving the app mid-onboarding would leave
    // the controller believing it was still in the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> controller.onEnterForeground()
                    Lifecycle.Event.ON_STOP -> controller.onLeaveForeground()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.onLeaveForeground()
        }
    }

    val canGoBack by controller.canGoBack.collectAsStateWithLifecycle()
    val headerStyle by controller.headerStyle.collectAsStateWithLifecycle()

    val account by controller.account.collectAsStateWithLifecycle()
    val screen by controller.screen.collectAsStateWithLifecycle()
    val onboarding by controller.onboarding.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val message by controller.message.collectAsStateWithLifecycle()
    val connected by controller.connectedRelays.collectAsStateWithLifecycle()
    val relayState by controller.relays.state.collectAsStateWithLifecycle()
    val mediaState by controller.media.state.collectAsStateWithLifecycle()

    var composing by remember { mutableStateOf(false) }

    // Transient messages float; the publish report, which is a page of relays
    // and their answers, stays as a card.
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val shown = message?.let { snackbars.show(it) } ?: false
        if (shown) controller.dismissMessage()
    }

    onboarding?.let { step ->
        // Onboarding is a sequence rather than a set of destinations, so it gets
        // its own back rule. The key backup screen swallows the press entirely:
        // it is the one and only showing of the nsec, and until now back there
        // closed the app with the key unsaved.
        BackHandler(enabled = true) {
            if (step !is OnboardingStep.Backup) controller.onboardingBack()
        }
        OnboardingSurface(controller, step)
        return
    }

    // Only claim the press when there is somewhere to go; leaving it unhandled
    // on Home is what lets the system close the app as it should.
    BackHandler(enabled = canGoBack) { controller.back() }

    if (composing) {
        ComposeChooser(
            onDismiss = { composing = false },
            onNote = {
                composing = false
                controller.go(Screen.Compose)
            },
            onArticle = {
                composing = false
                controller.go(Screen.EditArticle(null))
            },
        )
    }

    Scaffold(
        snackbarHost = { WayfarerSnackbarHost(snackbars) },
        topBar = {
            AppHeader(
                connected = connected.size,
                allowed = relayState.approved.size,
                waiting = relayState.pending.size,
                mediaWaiting = mediaState.pending.size,
                style = headerStyle,
                canGoBack = canGoBack,
                onBack = { controller.back() },
                onRelays = { controller.go(Screen.Relays) },
                onMedia = { controller.go(Screen.Media) },
            )
        },
        floatingActionButton = {
            // Only where there is a feed to add to. On the relay screen or a
            // profile it would be a button with no relationship to the page.
            if (screen is Screen.Home) {
                FloatingActionButton(
                    onClick = { composing = true },
                    // Moss, like every other button that ends in a signed event
                    // on somebody else's server. This is the most public one in
                    // the app, so it is the last place to leave a default.
                    containerColor = MaterialTheme.colorScheme.publicAccent,
                    contentColor = MaterialTheme.colorScheme.onPublicAccent,
                ) {
                    Icon(WayfarerIcons.Add, contentDescription = "Write something")
                }
            }
        },
        bottomBar = {
            Column {
                // Inside bottomBar rather than at the foot of HomeScreen's own
                // column, which is what fixes the collision: Scaffold anchors the
                // FAB above whatever bottomBar measures, so putting the arrows
                // here lifts the FAB clear of them. Before, the FAB floated over
                // the content and sat exactly on the "next" arrow — hiding it and
                // taking its taps, so advancing opened the compose sheet instead.
                if (screen is Screen.Home) PagingBar(controller)
                TabBar(
                    globalSelected =
                        screen is Screen.Home || screen is Screen.Compose ||
                            screen is Screen.EditArticle || screen is Screen.ReadArticle ||
                            screen is Screen.ReadNote,
                    localSelected =
                        screen is Screen.Profile || screen is Screen.EditProfile ||
                            screen is Screen.RelayList || screen is Screen.Follows,
                    onGlobal = { controller.goToRoot(Screen.Home) },
                    // Without an account there is no profile to open, so the tab goes
                    // where a signed-out user's options actually are.
                    onLocal = {
                        account?.let { controller.openProfileAsRoot(it.pubKey) } ?: controller.goToRoot(Screen.Settings)
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) WayfarerProgressBar(Modifier.fillMaxWidth())

            // Only what is not transient — everything else is in the snackbar
            // above, where it costs the page no room.
            message?.takeIf { !it.transient }?.let {
                MessageBanner(it, onDismiss = controller::dismissMessage)
            }

            when (val current = screen) {
                Screen.Home -> HomeScreen(controller)
                Screen.Compose -> ComposeNoteScreen(controller)
                Screen.Relays -> RelayScreen(controller)
                Screen.Media -> MediaScreen(controller)
                Screen.EditProfile -> EditProfileScreen(controller)
                Screen.Settings -> SettingsScreen(controller)
                Screen.RelayList -> RelayListScreen(controller)
                Screen.Follows -> FollowsScreen(controller)
                is Screen.EditArticle -> EditArticleScreen(controller, current.address)
                is Screen.ReadArticle -> ReadArticleScreen(controller, current.address)
                is Screen.ReadNote -> ReadNoteScreen(controller, current.id)
                is Screen.Profile -> ProfileScreen(controller, current.pubKey)
            }
        }
    }
}

/**
 * What the `+` offers.
 *
 * A note and an article are the same act from the user's side, so they are one
 * button with two answers rather than two tabs competing for a slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeChooser(
    onDismiss: () -> Unit,
    onNote: () -> Unit,
    onArticle: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Write something", style = MaterialTheme.typography.titleMedium)
            // Two outlined choices on the sheet's own ground, rather than
            // Material's ListItem — which paints a container of its own, so the
            // sheet had a second grey panel inside it whose only job was to sit
            // behind two lines of text and a divider.
            ComposeOption(
                title = "Note",
                detail = "A short post. Goes out to your write relays.",
                onClick = onNote,
            )
            ComposeOption(
                title = "Article",
                detail = "Long-form, with a title, and editable after publishing.",
                onClick = onArticle,
            )
        }
    }
}

/** One thing the `+` can make: a name, a line about it, and the whole row taps. */
@Composable
private fun ComposeOption(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            WayfarerIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.publicAccent,
        )
    }
}

/**
 * The app's header: one thin line, and the way to the relays.
 *
 * A plain Row rather than Material's TopAppBar, whose container height is fixed
 * at 64dp — a quarter of a phone's chrome spent on a status line. That means
 * taking on the status-bar inset ourselves, which the Scaffold slot would
 * otherwise have handled.
 *
 * The relay icon lives here rather than in the navigation bar because relays are
 * somewhere you visit occasionally and glance at constantly: the badge is the
 * point, the destination is secondary, and a permanent third of the bottom bar
 * was the wrong trade.
 */
@Composable
private fun AppHeader(
    connected: Int,
    allowed: Int,
    waiting: Int,
    /** Picture servers queued for a decision. The number on the pill. */
    mediaWaiting: Int,
    style: HeaderStyle,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onRelays: () -> Unit,
    onMedia: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            // The bar itself is the target, not just the icon on the end: every
            // word written in it is a relay count, so anywhere in it meaning
            // "show me the relays" is what a reader would expect. The two icons
            // and the back button keep their own taps — a child that handles a
            // click consumes it — which is what lets the pictures button sit
            // here without the surrounding bar swallowing it.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(if (style == HeaderStyle.Compact) 32.dp else 40.dp)
                    .clickable(onClick = onRelays)
                    .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(WayfarerIcons.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                }
            }

            if (style == HeaderStyle.Compact) {
                ConnectionDot(connected > 0)
                Text(
                    "$connected · $allowed" + if (waiting > 0) " · $waiting" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            } else {
                Text(
                    "$connected connected · $allowed allowed" + if (waiting > 0) " · $waiting waiting" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // Pictures live next to relays because they are the same kind of
            // decision — which servers may this app talk to — and these two
            // lists are the only things in the app that answer it.
            //
            // This one carries a mark where the relay icon does not: the line
            // to the left already says how many relays are waiting, and nothing
            // anywhere says that picture servers are. Now that the queue fills
            // itself as you read, the dot is the only thing standing between
            // "some hosts accumulated" and nobody ever finding out.
            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = onMedia, modifier = Modifier.size(36.dp)) {
                    Icon(WayfarerIcons.Image, contentDescription = "Pictures")
                }
                WaitingDot(mediaWaiting > 0)
            }

            // No count on the icon: the line to its left already says how many
            // are waiting, and the badge was the same number twice.
            IconButton(onClick = onRelays, modifier = Modifier.size(36.dp)) {
                Icon(WayfarerIcons.Relay, contentDescription = "Relays")
            }
        }
    }
}

/**
 * The two tabs, built rather than squeezed.
 *
 * Material's NavigationBar lays its items out at a fixed internal height and
 * adds its own window insets, so constraining the container to 64dp — which is
 * what this used to do — did not shrink the items, it clipped them. Measuring
 * from the content instead gives the same compact bar with nothing cut off, and
 * the inset padding that Material was applying now has to be applied here.
 */
@Composable
private fun TabBar(
    globalSelected: Boolean,
    localSelected: Boolean,
    onGlobal: () -> Unit,
    onLocal: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The app's two colours, on the one control that names the same
            // division: Moss for everything public, Trail for everything that
            // stays here. Tinting both tabs alike threw that away, and tinting
            // them the other way round taught the wrong pair.
            Tab(
                WayfarerIcons.Globe,
                "Global",
                globalSelected,
                onGlobal,
                MaterialTheme.colorScheme.publicAccent,
                Modifier.weight(1f),
            )
            Tab(
                WayfarerIcons.Tree,
                "Local",
                localSelected,
                onLocal,
                MaterialTheme.colorScheme.localAccent,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Tab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedTint: Color,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) selectedTint else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * That picture servers are waiting, as a dot on the icon.
 *
 * A dot rather than a count. The number was never the thing to act on — nobody
 * decides differently about three servers than about seven — and printing it in
 * a bar that is otherwise all numbers made a notification read as one more
 * statistic. Absent at zero, because a permanent badge is a notification about
 * the absence of news.
 */
@Composable
private fun WaitingDot(waiting: Boolean) {
    if (!waiting) return
    Box(
        Modifier
            .padding(top = 4.dp, end = 4.dp)
            .size(9.dp)
            // Ringed in the bar's own colour, so at any density the dot reads as
            // sitting on the icon rather than as part of it.
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(1.5.dp)
            .background(MaterialTheme.colorScheme.localAccent, CircleShape),
    )
}

/** Lit when something is connected, dim when nothing is. */
@Composable
private fun ConnectionDot(live: Boolean) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(8.dp)
            .background(
                // Trail: a relay connection is this phone talking to a server it
                // was allowed to talk to, which is the local half of the app.
                // It was drawn in Compass blue, so "connected" and "this is a
                // button" were the same colour in the one bar that means both.
                if (live) MaterialTheme.colorScheme.localAccent else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
    )
}

@Composable
fun LoadingScreen(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.publicAccent,
                trackColor = MaterialTheme.colorScheme.localAccent.copy(alpha = 0.35f),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
