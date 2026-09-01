package app.wayfarer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.wayfarer.android.viewmodel.UserMessage
import app.wayfarer.core.repo.PublishReport

/**
 * Shows a transient message. A publish result is rendered in full — which relays
 * took the event, which refused it and why — because with outbox routing that is
 * the only way a user can tell where their note actually went.
 */
@Composable
fun MessageBanner(
    message: UserMessage,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when (message) {
                        is UserMessage.Error -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (message) {
                is UserMessage.Error -> Text(message.text, style = MaterialTheme.typography.bodyMedium)
                is UserMessage.Info -> Text(message.text, style = MaterialTheme.typography.bodyMedium)
                is UserMessage.Published -> PublishSummary(message.report)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun PublishSummary(report: PublishReport) {
    val accepted = report.accepted
    val rejected = report.rejected

    Text(
        if (accepted.isEmpty()) "No relay accepted this event." else "Accepted by ${accepted.size} of ${report.outcomes.size} relays.",
        style = MaterialTheme.typography.titleSmall,
    )

    if (report.plan.mentionInbox.isNotEmpty()) {
        Text(
            "${report.plan.ownWrite.size} of these are your own write relays; " +
                "${report.plan.mentionInbox.size} are inbox relays of people you mentioned.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    for (relay in accepted.sorted()) {
        Text("accepted · ${relay.display()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    for ((relay, reason) in rejected.entries.sortedBy { it.key }) {
        Text(
            "refused · ${relay.display()} — ${reason.ifBlank { "no reason given" }}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        "event id ${report.event.id.hex.take(16)}…",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
    )
}
