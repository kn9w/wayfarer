package app.wayfarer.core

import app.wayfarer.core.model.DiscoverySource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every reason a relay can be queued for must be one the app can actually
 * produce.
 *
 * `CONTACT_LIST` used to sit in this enum with a sentence written for it and no
 * code path that could ever create one — `ContactRepository` never touched the
 * permission directory. It read as a working feature in the source and was
 * dead. This is the check that would have caught it.
 */
class DiscoverySourceTest {
    @Test
    fun `every source is produced somewhere in the app`() {
        val producers =
            mapOf(
                DiscoverySource.BOOTSTRAP to "RelayDirectory.suggest, seeded in Wayfarer.create",
                DiscoverySource.USER_ENTERED to "OutboxRouter.relayPlanFor and ThreadRepository.load",
                DiscoverySource.OWN_RELAY_LIST to "OutboxRouter publish/inbox plans, RelayListRepository",
                DiscoverySource.AUTHOR_RELAY_LIST to "OutboxRouter.readPlanFor and publishPlanFor",
                DiscoverySource.EVENT_HINT to "RelayDirectory.noteHint and the relay hint queue",
            )

        for (source in DiscoverySource.entries) {
            assertTrue(
                source in producers,
                "$source has no producer — either wire it up or remove it, but do not leave it looking real",
            )
        }
    }
}
