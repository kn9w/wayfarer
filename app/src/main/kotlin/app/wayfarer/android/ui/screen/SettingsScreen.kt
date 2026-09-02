package app.wayfarer.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.ui.ScreenHeader
import app.wayfarer.core.repo.ACTIVITY_WINDOW_CHOICES
import app.wayfarer.core.repo.HeaderStyle
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.core.repo.Credential

/**
 * Account and key settings.
 *
 * The half of key safety the backup screen cannot provide on its own: the key is
 * shown once during setup, and a key that could never be seen again would be one
 * bad moment away from being lost for good. Here it is behind the device's own
 * lock, on request, for as long as it is being read and no longer.
 */
@Composable
fun SettingsScreen(controller: AppController) {
    val account by controller.account.collectAsStateWithLifecycle()
    val revealed by controller.revealedSecretKey.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()

    var confirmingReveal by remember { mutableStateOf(false) }
    var confirmingLogout by remember { mutableStateOf(false) }

    if (confirmingReveal) {
        AlertDialog(
            onDismissRequest = { confirmingReveal = false },
            title = { Text("Show your secret key?") },
            text = {
                Text(
                    "It will appear on this screen until you hide it or leave. Anyone who can see the screen — " +
                        "or a camera pointed at it — can take the account.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmingReveal = false
                    controller.revealSecretKey()
                }) { Text("Show it") }
            },
            dismissButton = { TextButton(onClick = { confirmingReveal = false }) { Text("Cancel") } },
        )
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("Log out?") },
            text = {
                Text(
                    if (account?.credential is Credential.LocalKey) {
                        "Logging out erases this account's key from this phone. If you have not saved it " +
                            "anywhere, the account is gone — there is no reset. Show the key first if you are unsure."
                    } else {
                        "This account holds no key on this device, so logging out only forgets who you are here."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmingLogout = false
                    controller.logout()
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmingLogout = false }) { Text("Cancel") } },
        )
    }

    val activityWindow by controller.activityWindowDays.collectAsStateWithLifecycle()
    val headerStyle by controller.headerStyle.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = { Text("Settings", style = MaterialTheme.typography.titleLarge) })
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = account
            if (current == null) {
                Text("You are browsing without an account", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Reading needs no key, so nothing is missing from the feed. An account is what lets you post, " +
                        "follow people and have a profile.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = controller::beginIntroduction, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up an account")
                }
                OutlinedButton(onClick = controller::goToSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text("Log in with a key I already have")
                }
                return@Column
            }

            Text("Account", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(current.npub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when (current.credential) {
                            is Credential.LocalKey -> "The key is held on this phone, encrypted by Android's keystore."
                            is Credential.ExternalSigner ->
                                "Signing happens in your signer app. Wayfarer has never seen this key, so it " +
                                    "cannot show it to you — back it up there."
                            Credential.WatchOnly ->
                                "Added with an npub, so this is a read-only view of somebody's account. There is " +
                                    "no key here and nothing can be posted."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (current.hasLocalKey) {
                HorizontalDivider()
                Text("Your secret key", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your nsec is stored on this phone, encrypted with a key that never leaves Android's keystore. " +
                        "You can read it back here whenever you need to move the account to another app.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                when (val nsec = revealed) {
                    null ->
                        Button(
                            onClick = { confirmingReveal = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Show my secret key") }
                    else -> {
                        OutlinedTextField(
                            value = nsec,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("nsec") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Selectable, so copying it is your own deliberate act — Wayfarer never writes it to the " +
                                "clipboard on its own.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = controller::hideSecretKey, modifier = Modifier.fillMaxWidth()) {
                            Text("Hide it")
                        }
                    }
                }
                Text(
                    "Your phone will ask you to confirm first, if it has a screen lock.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            HorizontalDivider()
            Text("Browsing", style = MaterialTheme.typography.titleMedium)
            Text(
                "How recently somebody must have posted to count as active, for the activity filter on the " +
                    "Global screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                for (days in ACTIVITY_WINDOW_CHOICES) {
                    FilterChip(
                        selected = days == activityWindow,
                        onClick = { controller.setActivityWindowDays(days) },
                        label = { Text(if (days == 1) "1 day" else "$days days") },
                    )
                }
            }
            Text(
                // The same caveat the funnel carries: this is judged from what
                // has been fetched, not from what exists.
                "Judged only from posts Wayfarer has actually fetched — somebody publishing to relays you have " +
                    "not allowed will look quiet however often they write.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Header", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (style in HeaderStyle.entries) {
                    FilterChip(
                        selected = style == headerStyle,
                        onClick = { controller.setHeaderStyle(style) },
                        label = { Text(style.name) },
                    )
                }
            }
            Text(
                "Compact trades the words for a connection dot and bare counts, and gives the few pixels back " +
                    "to what you came to read.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Relays", style = MaterialTheme.typography.titleMedium)
            Text(
                "Which servers this app may talk to, and what each one is allowed to do. The list is kept on this " +
                    "phone and is not published anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { controller.go(Screen.Relays) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open relay settings")
            }

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { confirmingLogout = true }, enabled = !busy) { Text("Log out") }
            }
        }
    }
}
