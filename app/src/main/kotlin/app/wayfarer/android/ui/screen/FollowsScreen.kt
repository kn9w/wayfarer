package app.wayfarer.android.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.core.model.PubKey
import app.wayfarer.core.repo.FollowSource

/**
 * Who this account reads, and by which route.
 *
 * The two lists are shown as one, because "who do I read" is one question, with
 * each row saying where that follow lives — the same shape the relay screen
 * uses for permissions. Unfollowing acts on the list the row names, so removing
 * somebody from this phone never quietly rewrites a public list, and removing
 * them publicly never silently drops the private one.
 */
@Composable
fun FollowsScreen(controller: AppController) {
    val published by controller.publishedFollows.collectAsStateWithLifecycle()
    val local by controller.localFollows.collectAsStateWithLifecycle()
    val everyone = (published + local).sortedBy { controller.displayName(it).lowercase() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Following", style = MaterialTheme.typography.titleMedium)
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Two ways to follow", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "A public follow is a signed note listing everybody you follow, which every other client can " +
                            "read. A follow kept on this phone is published nowhere and told to no relay — it only " +
                            "decides what Wayfarer fetches for you.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (everyone.isEmpty()) {
            item {
                Text(
                    "You follow nobody yet. Open somebody's profile to follow them, either way.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(everyone, key = { it.hex }) { person ->
            FollowRow(
                person = person,
                sources = controller.followSourcesOf(person),
                controller = controller,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FollowRow(
    person: PubKey,
    sources: Set<FollowSource>,
    controller: AppController,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { controller.openProfile(person) }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(controller.displayName(person), style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                sources.containsAll(listOf(FollowSource.Published, FollowSource.Local)) ->
                    "on your public list and on this phone"
                FollowSource.Published in sources -> "on your public follow list"
                else -> "on this phone only"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (FollowSource.Published in sources) {
                TextButton(onClick = { controller.unfollowPublicly(person) }) { Text("Unfollow publicly") }
            }
            if (FollowSource.Local in sources) {
                TextButton(onClick = { controller.unfollowLocally(person) }) { Text("Remove from this phone") }
            }
        }
    }
}
