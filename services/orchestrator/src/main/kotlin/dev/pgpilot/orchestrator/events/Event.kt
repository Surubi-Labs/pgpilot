package dev.pgpilot.orchestrator.events

import java.time.Instant
import java.util.UUID

/**
 * An append-only entry in `pgpilot.events`.
 *
 * @property source Origin of the event: `internal` for system-generated
 *   signals, or `webhook:<provider>` for external deliveries
 *   (`webhook:stripe`, `webhook:github`, …).
 * @property externalId Upstream event id used for dedup; null for internal
 *   signals.
 * @property payload JSON payload as a string. Used raw by event matching
 *   (`wait_match @> payload`) so the JSON structure is preserved on
 *   insert.
 */
data class Event(
    val id: UUID,
    val type: String,
    val source: String,
    val externalId: String?,
    val payload: String,
    val receivedAt: Instant,
) {
    companion object {
        const val SOURCE_INTERNAL: String = "internal"

        fun webhookSource(provider: String): String = "webhook:$provider"
    }
}
