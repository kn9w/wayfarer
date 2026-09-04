package app.wayfarer.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.platform.SecureScreen
import app.wayfarer.android.ui.ScreenHeader
import app.wayfarer.android.ui.Avatar
import app.wayfarer.android.ui.icons.WayfarerIcons
import app.wayfarer.core.repo.ACTIVITY_WINDOW_CHOICES
import app.wayfarer.core.repo.HeaderStyle
import app.wayfarer.android.ui.theme.localButtonColors
import app.wayfarer.android.ui.theme.localOutlinedButtonColors
import app.wayfarer.android.ui.theme.publicAccent
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.Screen
import app.wayfarer.android.viewmodel.shortenNpub
import app.wayfarer.core.repo.Credential
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.repo.Account
import app.wayfarer.core.repo.AccountSummary
import app.wayfarer.core.repo.CredentialKind

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
    // The key can be put on this screen, so the screen stays off every capture
    // path — including the recents snapshot, which the lock screen in front of
    // the reveal cannot help with.
    SecureScreen()

    val account by controller.account.collectAsStateWithLifecycle()
    val accounts by controller.accounts.collectAsStateWithLifecycle()
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
        val others = accounts.count { it.pubKey != account?.pubKey }
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("Log out of this account?") },
            text = {
                // Itemised, because logging out erases things that are not
                // obviously part of "logging out" — a private follow list, two
                // permission lists, the pictures already fetched — and a
                // sentence saying "everything" is not something anybody can
                // check their own understanding against.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nothing about this account stays on this phone. Wayfarer erases:")

                    ErasedItem(
                        if (account?.credential is Credential.LocalKey) {
                            "its secret key — if you have not saved it anywhere, the account is gone, and there " +
                                "is no reset"
                        } else {
                            "how it signs in — no key is held here for it"
                        },
                    )
                    ErasedItem("the relays it allowed, and the picture servers it allowed")
                    ErasedItem("the follow list kept on this phone, which was never published anywhere")
                    ErasedItem("every picture already fetched while it was signed in")

                    Text(
                        "Its public follow list, its profile and its posts are on the network and are not " +
                            "affected. Signing in again starts from nothing on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (others > 0) {
                        Text(
                            if (others == 1) {
                                "The other account signed in here is untouched, and takes over when this one goes."
                            } else {
                                "The other $others accounts signed in here are untouched, and one takes over " +
                                    "when this one goes."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (account?.credential is Credential.LocalKey) {
                        Text(
                            "Show the key first if you are unsure.",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                // Trail: logging out changes this phone and tells no relay
                // anything — the key is erased here, not withdrawn from anywhere.
                Button(
                    onClick = {
                        confirmingLogout = false
                        controller.logout()
                    },
                    colors = localButtonColors(),
                ) { Text("Log out") }
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

            AccountsSection(
                controller = controller,
                current = current,
                others = accounts,
                onLogOut = { confirmingLogout = true },
            )

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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                            // As on the backup screen: shown, because that is what
                            // was asked for, but declared a password so autofill and
                            // the keyboard leave it alone.
                            keyboardOptions =
                                KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().semantics { password() },
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
        }
    }
}

/** One line of what logging out destroys, marked as a list rather than prose. */
@Composable
private fun ErasedItem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Who is signed in, and the others who also are.
 *
 * More than one account can be signed in at once, so this is a switcher rather
 * than a status line: the active one at the top with what it can do and the way
 * out, everybody else as a row that takes one tap. Nostr identities are cheap
 * and people keep several on purpose, and an app that holds one makes using the
 * second mean destroying the first.
 */
@Composable
private fun AccountsSection(
    controller: AppController,
    current: Account,
    others: List<AccountSummary>,
    onLogOut: () -> Unit,
) {
    Text("Accounts", style = MaterialTheme.typography.titleMedium)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountRow(
                controller = controller,
                pubKey = current.pubKey,
                npub = current.npub,
                kind = current.credential.kind(),
                active = true,
                onClick = null,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Trail: signing out changes this phone. It publishes nothing,
                // and what it erases is what this phone was allowed to do.
                OutlinedButton(onClick = onLogOut, colors = localOutlinedButtonColors()) { Text("Log out") }
                TextButton(onClick = controller::addAnotherAccount) { Text("Add another account") }
            }

            val rest = others.filter { it.pubKey != current.pubKey }
            if (rest.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Also signed in",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (other in rest) {
                    AccountRow(
                        controller = controller,
                        pubKey = other.pubKey,
                        npub = other.npub,
                        kind = other.kind,
                        active = false,
                        onClick = { controller.switchTo(other.pubKey) },
                    )
                }
            }
        }
    }
}

/**
 * One identity: its mark, what to call it, its key, and how it signs.
 *
 * The whole row taps when it is somebody to switch to, and does nothing when it
 * is already the one in use — a row that looks pressable and is not is worse
 * than one that plainly is not.
 */
@Composable
private fun AccountRow(
    controller: AppController,
    pubKey: PubKey,
    npub: String,
    kind: CredentialKind,
    active: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(pubKey = pubKey, controller = controller, size = 40.dp)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                controller.displayName(pubKey),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                shortenNpub(npub),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            kind.describe(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (active) {
            Icon(
                WayfarerIcons.Check,
                contentDescription = "Signed in as this account",
                tint = MaterialTheme.colorScheme.publicAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** What an account can do, in two words. */
private fun CredentialKind.describe(): String =
    when (this) {
        CredentialKind.LocalKey -> "key here"
        CredentialKind.ExternalSigner -> "signer app"
        CredentialKind.WatchOnly -> "read only"
    }

private fun Credential.kind(): CredentialKind =
    when (this) {
        is Credential.LocalKey -> CredentialKind.LocalKey
        is Credential.ExternalSigner -> CredentialKind.ExternalSigner
        Credential.WatchOnly -> CredentialKind.WatchOnly
    }
