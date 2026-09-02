package app.wayfarer.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.rootRefOfNote
import app.wayfarer.core.model.EventId

/**
 * One note, on its own.
 *
 * A note in a list is a fragment competing with its neighbours for attention;
 * this is the same note with room to be read, and its conversation opened
 * rather than waiting behind an affordance — somebody who navigated here came
 * for the whole thing.
 */
@Composable
fun ReadNoteScreen(
    controller: AppController,
    id: EventId,
) {
    val notes by controller.allNotes.collectAsStateWithLifecycle()
    val replies by controller.threadReplies.collectAsStateWithLifecycle()
    val note = notes[id] ?: replies[id]

    // A note reached from a profile is already held, but a thread fetched under
    // it is not; opening it here is what fills the conversation in.
    LaunchedEffect(id) { controller.threads.open(rootRefOfNote(id)) }

    if (note == null) {
        Text("This note is no longer loaded.", Modifier.padding(12.dp))
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PostByline(
            name = controller.displayName(note.author),
            createdAt = note.createdAt,
            controller = controller,
            onOpenAuthor = { controller.openProfile(note.author) },
            trailing = { EventMenu(note.id, controller) },
        )

        note.replyTo?.let { parent ->
            Text(
                controller.replyContextFor(parent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(note.content, style = MaterialTheme.typography.bodyLarge)

        Text(
            "seen on " + note.seenOn.joinToString(", ") { it.display() }.ifBlank { "this device" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )

        HorizontalDivider(Modifier.fillMaxWidth())

        ThreadSection(
            root = rootRefOfNote(note.id),
            rootKind = "1",
            rootAuthor = note.author,
            controller = controller,
        )
    }
}
