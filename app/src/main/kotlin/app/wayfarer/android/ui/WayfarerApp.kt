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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.screen.BackupScreen
import app.wayfarer.android.ui.screen.ComposeNoteScreen
import app.wayfarer.android.ui.screen.EditProfileScreen
import app.wayfarer.android.ui.screen.HomeScreen
import app.wayfarer.android.ui.screen.OnboardingScreen
import app.wayfarer.android.ui.screen.ProfileScreen
import app.wayfarer.android.ui.screen.RelayScreen
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.core.Wayfarer
import kotlinx.coroutines.CoroutineScope

/**
 * The whole UI tree.
 *
 * Navigation is a single state value rather than a navigation library: this app
 * has six screens and no deep links, and the dependency would outweigh the
 * `when` block below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WayfarerApp(
    core: Wayfarer,
    scope: CoroutineScope,
) {
    val controller = remember(core) { AppController(core, scope) }

    val account by controller.account.collectAsStateWithLifecycle()
    val screen by controller.screen.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val message by controller.message.collectAsStateWithLifecycle()
    val connected by controller.connectedRelays.collectAsStateWithLifecycle()
    val relayState by controller.relays.state.collectAsStateWithLifecycle()

    if (account == null) {
        OnboardingScreen(
            busy = busy,
            message = message,
            onCreate = controller::createAccount,
            onLogin = controller::login,
            onDismissMessage = controller::dismissMessage,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(screen.title(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${connected.size} connected · ${relayState.approved.size} approved" +
                                if (relayState.pending.isNotEmpty()) " · ${relayState.pending.size} awaiting approval" else "",
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
                    onClick = { account?.let { controller.openProfile(it.pubKey) } },
                    icon = { NavIcon("Me") },
                    label = { Text("Profile") },
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
                is Screen.Profile -> ProfileScreen(controller, current.pubKey)
                is Screen.Backup -> BackupScreen(current.nsec) { controller.go(Screen.Relays) }
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
        is Screen.Profile -> "Profile"
        is Screen.Backup -> "Back up your key"
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
