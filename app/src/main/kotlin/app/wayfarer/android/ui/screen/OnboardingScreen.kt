package app.wayfarer.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.wayfarer.android.ui.MessageBanner
import app.wayfarer.android.viewmodel.UserMessage

@Composable
fun OnboardingScreen(
    busy: Boolean,
    message: UserMessage?,
    onCreate: () -> Unit,
    onLogin: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var key by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Wayfarer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A small nostr client that follows the outbox model, and connects to no relay you have not approved.",
            style = MaterialTheme.typography.bodyMedium,
        )

        message?.let { MessageBanner(it, onDismissMessage) }

        Button(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Create a new account")
        }
        Text(
            "Generates a key on this device. You will be shown the nsec once — that string is the account, so keep it somewhere safe.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("npub or nsec") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onLogin(key) },
            enabled = !busy && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Log in")
        }
        Text(
            "An nsec signs and publishes. An npub is watch-only: you can read everything and publish nothing.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun BackupScreen(
    nsec: String,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Back up your key", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This is your account. Anyone holding it is you, and nobody — including this app — can recover it if you lose it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        // Selectable rather than a copy button: no clipboard write the user did
        // not ask for, and no other app gets handed the key by accident.
        OutlinedTextField(
            value = nsec,
            onValueChange = {},
            readOnly = true,
            label = { Text("nsec") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Next: choose which relays this app may talk to. Until you approve at least one, Wayfarer opens no connections at all.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Choose relays")
        }
    }
}
