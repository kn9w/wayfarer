package app.wayfarer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.screen.ArticleListScreen
import app.wayfarer.android.ui.screen.EditArticleScreen
import app.wayfarer.android.ui.screen.ReadArticleScreen
import app.wayfarer.android.ui.screen.ComposeNoteScreen
import app.wayfarer.android.ui.screen.EditProfileScreen
import app.wayfarer.android.ui.screen.HomeScreen
import app.wayfarer.android.ui.screen.OnboardingSurface
import app.wayfarer.android.ui.screen.ProfileScreen
import app.wayfarer.android.ui.screen.RelayListScreen
import app.wayfarer.android.ui.screen.RelayScreen
import app.wayfarer.android.ui.screen.SettingsScreen
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.DeviceAuthOutcome
import app.wayfarer.android.viewmodel.ExternalSignerIdentity
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.core.Wayfarer
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

    val account by controller.account.collectAsStateWithLifecycle()
    val screen by controller.screen.collectAsStateWithLifecycle()
    val onboarding by controller.onboarding.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val message by controller.message.collectAsStateWithLifecycle()
    val connected by controller.connectedRelays.collectAsStateWithLifecycle()
    val relayState by controller.relays.state.collectAsStateWithLifecycle()

    onboarding?.let { step ->
        OnboardingSurface(controller, step)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(screen.title(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${connected.size} connected · ${relayState.approved.size} allowed" +
                                if (relayState.pending.isNotEmpty()) " · ${relayState.pending.size} waiting" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen is Screen.Home,
                    onClick = { controller.go(Screen.Home) },
                    icon = { NavIcon("Feed") },
                    label = { Text("Feed") },
                )
                NavigationBarItem(
                    selected = screen is Screen.Compose,
                    onClick = { controller.go(Screen.Compose) },
                    icon = { NavIcon("Post") },
                    label = { Text("Post") },
                )
                NavigationBarItem(
                    selected = screen is Screen.Profile || screen is Screen.EditProfile,
                    // Without an account there is no profile to open, so the tab goes
                    // where a signed-out user's options actually are.
                    onClick = {
                        account?.let { controller.openProfile(it.pubKey) } ?: controller.go(Screen.Settings)
                    },
                    icon = { NavIcon("Me") },
                    label = { Text("Profile") },
                )
                NavigationBarItem(
                    selected = screen is Screen.Articles || screen is Screen.EditArticle || screen is Screen.ReadArticle,
                    onClick = { controller.go(Screen.Articles) },
                    icon = { NavIcon("Write") },
                    label = { Text("Write") },
                )
                NavigationBarItem(
                    selected = screen is Screen.Relays,
                    onClick = { controller.go(Screen.Relays) },
                    icon = { NavIcon(if (relayState.pending.isEmpty()) "Relays" else "Relays!") },
                    label = { Text("Relays") },
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
                Screen.Articles -> ArticleListScreen(controller)
                Screen.Settings -> SettingsScreen(controller)
                Screen.RelayList -> RelayListScreen(controller)
                is Screen.EditArticle -> EditArticleScreen(controller, current.address)
                is Screen.ReadArticle -> ReadArticleScreen(controller, current.address)
                is Screen.Profile -> ProfileScreen(controller, current.pubKey)
            }
        }
    }
}

private fun Screen.title(): String =
    when (this) {
        Screen.Home -> "Wayfarer"
        Screen.Compose -> "New note"
        Screen.Relays -> "Relays"
        Screen.EditProfile -> "Edit profile"
        Screen.Articles -> "Articles"
        Screen.Settings -> "Settings"
        Screen.RelayList -> "Where to find me"
        is Screen.EditArticle -> if (address == null) "New article" else "Edit article"
        is Screen.ReadArticle -> "Article"
        is Screen.Profile -> "Profile"
    }

/**
 * Text stands in for icons on purpose: `material-icons-extended` is a large
 * dependency for six glyphs, and the app's goal is to carry as few as it can.
 */
@Composable
private fun NavIcon(label: String) {
    Text(label, style = MaterialTheme.typography.labelSmall)
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
