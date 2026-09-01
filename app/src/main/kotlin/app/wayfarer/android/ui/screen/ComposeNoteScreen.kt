package app.wayfarer.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.Screen

@Composable
fun ComposeNoteScreen(controller: AppController) {
    val account by controller.account.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (account?.canSign == false) {
            Text(
                "This account was added with an npub, so it holds no key and cannot publish. " +
                    "Log in with an nsec to post.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What's happening?") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Any npub you paste becomes a mention: it is p-tagged, and the note is also sent to that " +
                "person's own read relays so it actually reaches them.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { controller.post(text) },
            enabled = !busy && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Publish") }
        TextButton(onClick = { controller.go(Screen.Home) }) { Text("Cancel") }
    }
}
