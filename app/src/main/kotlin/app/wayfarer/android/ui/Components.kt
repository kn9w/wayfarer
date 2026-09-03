package app.wayfarer.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wayfarer.android.platform.QrRender
import app.wayfarer.android.ui.theme.localAccent
import app.wayfarer.android.ui.theme.publicAccent
import app.wayfarer.android.viewmodel.UserMessage
import app.wayfarer.core.repo.PublishReport

/**
 * A screen's own title bar, inside its content.
 *
 * The app bar above is deliberately thin — a status line and a back arrow — so
 * each screen carries its own title and the actions that belong to it, rather
 * than one shared bar growing a slot for every screen's needs. That is why the
 * Global screen can put a mode dropdown where its title goes, and Relays can put
 * an explainer button on the right.
 */
@Composable
fun ScreenHeader(
    title: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = title)
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * The one progress bar in the app, in the app's own two colours.
 *
 * Material draws an indeterminate bar in primary on a `surfaceVariant` track,
 * which is a green line on a grey one. Moss on Trail instead: the two colours
 * everything else is sorted by, and no third neutral introduced for the one
 * control that reports the app talking to somebody.
 */
@Composable
fun WayfarerProgressBar(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier = modifier,
        color = MaterialTheme.colorScheme.publicAccent,
        trackColor = MaterialTheme.colorScheme.localAccent.copy(alpha = 0.35f),
    )
}

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

/**
 * A small tappable pill.
 *
 * The profile header's unit of identity: an npub, a lightning address, a
 * website. Each was a bare line of text before, and four of them stacked read as
 * one undifferentiated block of metadata rather than as four separate things a
 * reader might want to do something with.
 */
@Composable
fun IdentityChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontFamily = if (monospace) FontFamily.Monospace else null,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * Somebody's npub as a scannable code.
 *
 * On a white card whatever the theme, because the quiet zone around a QR code is
 * part of the code: drawn straight onto a dark background, the border modules
 * merge into it and readers give up.
 */
@Composable
fun NpubQrCard(
    npub: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 240.dp,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.roundToPx() }
    val code = remember(npub, sizePx) { QrRender.encode(npub, sizePx) }

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (code == null) {
            Text(
                "This key could not be drawn as a code.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black,
            )
        } else {
            Image(
                bitmap = code,
                contentDescription = "QR code of this npub",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(sizeDp),
            )
        }
    }
}
