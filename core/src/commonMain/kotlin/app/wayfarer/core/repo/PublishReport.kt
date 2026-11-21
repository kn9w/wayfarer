package app.wayfarer.core.repo

import app.wayfarer.core.model.NostrEvent
import app.wayfarer.core.model.RelayUrl
import app.wayfarer.core.nostr.PublishOutcome
import app.wayfarer.core.outbox.OutboxRouter

/**
 * What happened when an event was published.
 *
 * Deliberately detailed: with outbox routing the interesting question is not
 * "did it publish" but "which relays took it, and were the mentioned users'
 * inboxes among them". The UI shows this verbatim.
 */
data class PublishReport(
    val event: NostrEvent,
    val plan: OutboxRouter.PublishPlan,
    val outcomes: Map<RelayUrl, PublishOutcome>,
) {
    val accepted: Set<RelayUrl> get() = outcomes.filterValues { it.accepted }.keys

    val rejected: Map<RelayUrl, String> get() = outcomes.filterValues { !it.accepted }.mapValues { it.value.message }

    val anyAccepted: Boolean get() = outcomes.values.any { it.accepted }

    companion object {
        /** No approved write relay, so nothing was even attempted. */
        fun nowhereToPublish(
            event: NostrEvent,
            plan: OutboxRouter.PublishPlan,
        ) = PublishReport(event, plan, emptyMap())
    }
}

sealed interface PublishError {
    data object NotSignedIn : PublishError

    data object WatchOnlyAccount : PublishError

    data object NoApprovedWriteRelay : PublishError

    data class Rejected(
        val report: PublishReport,
    ) : PublishError
}

sealed interface PublishResult {
    data class Success(
        val report: PublishReport,
    ) : PublishResult

    data class Failure(
        val error: PublishError,
    ) : PublishResult
}
