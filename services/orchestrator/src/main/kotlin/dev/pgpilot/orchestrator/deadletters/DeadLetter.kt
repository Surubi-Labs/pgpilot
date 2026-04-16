package dev.pgpilot.orchestrator.deadletters

import java.time.Instant
import java.util.UUID

/**
 * A retries-exhausted (or operator-failed) run/step awaiting replay or
 * archive.
 *
 * Either [runId] or [stepId] must be set — a dead letter always points
 * back at the thing that failed. Both are allowed when a step failure
 * is coupled with a specific run.
 *
 * @property reason Short machine-readable category (`retry_exhausted`,
 *   `timeout`, `manual_fail`, …). Not an enum — the set grows as new
 *   failure categories surface.
 * @property replayable When false, the row has been archived and will
 *   not appear in the dashboard's replay queue.
 * @property replayedAt Set when an operator (or the replay flow) has
 *   finished retrying the underlying run/step.
 */
data class DeadLetter(
    val id: UUID,
    val runId: UUID?,
    val stepId: UUID?,
    val reason: String,
    val lastError: String?,
    val attempts: Int,
    val replayable: Boolean,
    val createdAt: Instant,
    val replayedAt: Instant?,
) {
    init {
        require(runId != null || stepId != null) {
            "DeadLetter must reference at least one of runId or stepId"
        }
    }
}
