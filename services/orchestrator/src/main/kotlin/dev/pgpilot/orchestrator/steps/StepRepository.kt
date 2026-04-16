package dev.pgpilot.orchestrator.steps

import java.util.UUID

/**
 * Read/write access to `pgpilot.steps`.
 *
 * Covers structural CRUD + lifecycle transitions used by the orchestrator's
 * step executor (EPIC 3) and the dashboard. The SKIP LOCKED-style event
 * matching query that wakes `WAITING` steps when events arrive lives in a
 * dedicated class in EPIC 4.
 */
interface StepRepository {
    fun insert(step: Step): Step

    fun findById(id: UUID): Step?

    /** Memoization lookup: returns the durable row for a `step.run("name", ...)` call, or null. */
    fun findByRunIdAndName(
        runId: UUID,
        name: String,
    ): Step?

    /** All steps for a run, ordered by creation time ascending. */
    fun listByRunId(runId: UUID): List<Step>

    /** All children of a given fan step, ordered by `fan_index`. */
    fun listByFanGroupId(fanGroupId: UUID): List<Step>

    /**
     * Transitions to RUNNING and stamps `started_at` + bumps `attempt`.
     * Returns true if the row moved out of PENDING / SLEEPING / WAITING
     * and into RUNNING.
     */
    fun markRunning(id: UUID): Boolean

    /**
     * Transitions to COMPLETED, stores [output], stamps `completed_at`.
     * Returns true if the row was updated (was not already terminal).
     */
    fun markCompleted(
        id: UUID,
        output: String,
    ): Boolean

    /**
     * Transitions to FAILED, stores [error], stamps `completed_at`.
     * Returns true if the row was updated (was not already terminal).
     */
    fun markFailed(
        id: UUID,
        error: String,
    ): Boolean

    /**
     * Transitions to DEAD_LETTERED (retries exhausted). Stamps
     * `completed_at` if not already set so terminal invariants hold.
     */
    fun markDeadLettered(
        id: UUID,
        error: String,
    ): Boolean

    /** Total count across the schema; cheap diagnostic for tests + admin. */
    fun count(): Long
}
