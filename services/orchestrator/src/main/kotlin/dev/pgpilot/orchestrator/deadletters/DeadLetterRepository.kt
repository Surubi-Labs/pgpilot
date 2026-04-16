package dev.pgpilot.orchestrator.deadletters

import java.util.UUID

/**
 * Read/write access to `pgpilot.dead_letters`.
 *
 * Dead letters are append-plus-status: rows are never hard-deleted, but
 * `replayable` and `replayed_at` are flipped as operators process them.
 */
interface DeadLetterRepository {
    fun insert(deadLetter: DeadLetter): DeadLetter

    fun findById(id: UUID): DeadLetter?

    fun listByRunId(runId: UUID): List<DeadLetter>

    fun listByStepId(stepId: UUID): List<DeadLetter>

    /** Open dead letters, newest first — what the dashboard shows. */
    fun listReplayable(limit: Int): List<DeadLetter>

    /**
     * Stamps [DeadLetter.replayedAt] on the given dead letter. Returns
     * true if the row was updated (was still open).
     */
    fun markReplayed(id: UUID): Boolean

    /**
     * Marks a dead letter as archived (no longer replayable). Returns
     * true if the row was updated.
     */
    fun archive(id: UUID): Boolean

    fun count(): Long
}
