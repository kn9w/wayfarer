package app.wayfarer.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.icons.WayfarerIcons
import app.wayfarer.android.ui.screen.ComposeNoteScreen
import app.wayfarer.android.ui.screen.EditArticleScreen
import app.wayfarer.android.ui.screen.EditProfileScreen
import app.wayfarer.android.ui.screen.HomeScreen
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
) {
    // Held through rememberUpdatedState and read at call time. The activity passes
    // a capturing lambda, so a fresh instance arrives on every recomposition; using
    // it as a `remember` key rebuilt the controller each frame, which re-ran startup
    // and reset `screenState` to Home so no navigation could ever stick.
    val currentSignerLogin = rememberUpdatedState(externalSignerLogin)
    val currentDeviceAuth = rememberUpdatedState(deviceAuth)
    val currentQrScan = rememberUpdatedState(qrScan)
    val controller =
        remember(core) {
            AppController(
                core = core,
                scope = scope,
                externalSignerLogin = { currentSignerLogin.value },
                deviceAuth = { currentDeviceAuth.value },
                qrScan = { currentQrScan.value },
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

    var composing by remember { mutableStateOf(false) }

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
        topBar = {
            AppHeader(
                connected = connected.size,
                allowed = relayState.approved.size,
                waiting = relayState.pending.size,
                style = headerStyle,
                canGoBack = canGoBack,
                onBack = { controller.back() },
                onRelays = { controller.go(Screen.Relays) },
            )
        },
        floatingActionButton = {
            // Only where there is a feed to add to. On the relay screen or a
            // profile it would be a button with no relationship to the page.
            if (screen is Screen.Home) {
                FloatingActionButton(onClick = { composing = true }) {
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
                    localSelected = screen is Screen.Profile || screen is Screen.EditProfile || screen is Screen.RelayList,
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            message?.let {
                MessageBanner(it, onDismiss = controller::dismissMessage)
            }

            when (val current = screen) {
                Screen.Home -> HomeScreen(controller)
                Screen.Compose -> ComposeNoteScreen(controller)
                Screen.Relays -> RelayScreen(controller)
                Screen.EditProfile -> EditProfileScreen(controller)
                Screen.Settings -> SettingsScreen(controller)
                Screen.RelayList -> RelayListScreen(controller)
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
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Write something",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Note") },
                supportingContent = { Text("A short post. Goes out to your write relays.") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onNote),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Article") },
                supportingContent = { Text("Long-form, with a title, and editable after publishing.") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onArticle),
            )
        }
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
    style: HeaderStyle,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onRelays: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            // The whole bar is the target, not just the icon on the end. Every
            // word in it is about relays, so anywhere in it meaning "show me the
            // relays" is what a reader would expect. The back button keeps its
            // own tap: a child that handles a click consumes it.
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
            Tab(WayfarerIcons.Globe, "Global", globalSelected, onGlobal, Modifier.weight(1f))
            Tab(WayfarerIcons.Tree, "Local", localSelected, onLocal, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

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

/** Green when something is connected, dim when nothing is. */
@Composable
private fun ConnectionDot(live: Boolean) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(8.dp)
            .background(
                if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
    )
}

@Composable
fun LoadingScreen(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
