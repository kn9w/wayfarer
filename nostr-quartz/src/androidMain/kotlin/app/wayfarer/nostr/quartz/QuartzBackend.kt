package app.wayfarer.nostr.quartz

import app.wayfarer.core.NostrBackend
import app.wayfarer.core.nostr.RelayTransport
import app.wayfarer.core.relay.RelayDirectory
import kotlinx.coroutines.CoroutineScope

/**
 * The whole Quartz backend, assembled.
 *
 * This is the single line the app calls to plug Quartz into the core. Replacing
 * Quartz means writing another function of this shape; nothing in `core` or the
 * UI refers to this module by name beyond the one call site.
 */
fun quartzBackend(scope: CoroutineScope): NostrBackend =
    NostrBackend(
        codec = QuartzNostrCodec(),
        bech32 = QuartzBech32Codec(),
        keyTool = QuartzKeyTool(),
        normalizer = quartzRelayUrlNormalizer,
        signerFactory = quartzSignerFactory,
        clock = quartzClock,
        transportFactory = { directory: RelayDirectory -> quartzTransport(directory, scope) },
    )

private fun quartzTransport(
    directory: RelayDirectory,
    scope: CoroutineScope,
): RelayTransport = QuartzRelayTransport(policy = directory, scope = scope)
