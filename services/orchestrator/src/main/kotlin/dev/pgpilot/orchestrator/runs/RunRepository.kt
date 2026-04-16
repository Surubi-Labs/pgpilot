package dev.pgpilot.orchestrator.runs

import java.util.UUID

/**
 * Read/write access to `pgpilot.runs`.
 *
 * The claim loop (FOR UPDATE SKIP LOCKED) lives in a dedicated class in
 * EPIC 3; this repository covers CRUD + lifecycle transitions used by the
 * API gateway and the orchestrator's non-claim paths.
 */
interface RunRepository {
    /**
     * Inserts a run. Caller owns assignment of id + timestamps.
     *
     * @throws org.springframework.dao.DuplicateKeyException on PK or
     *         idempotency-key clash (use [findByIdempotencyKey] first if
     *         you want explicit dedup semantics).
     */
    fun insert(run: Run): Run

    /** Fetches a run by id, or null if missing. */
    fun findById(id: UUID): Run?

    /** Fetches a run by its customer-supplied idempotency key, or null. */
    fun findByIdempotencyKey(idempotencyKey: String): Run?

    /**
     * Inserts [run] unless a row with the same idempotency key already
     * exists, in which case the existing row is returned. Idempotent
     * semantics for `pgpilot.trigger(...)` retries.
     *
     * If [Run.idempotencyKey] is null this degenerates to a plain insert.
     */
    fun insertIdempotent(run: Run): Run

    /**
     * Transitions `status` to `running`, stamps `started_at` + `claimed_by`
     * + `claimed_at`, bumps `attempt`. Returns true if the row was updated
     * (existing status was `pending` or `running`); false if the run is
     * already terminal or does not exist.
     */
    fun markRunning(
        id: UUID,
        claimedBy: String,
    ): Boolean

    /**
     * Transitions to `completed`, stamps `completed_at`, stores [output],
     * clears claim fields. Returns true if the row was updated.
     */
    fun markCompleted(
        id: UUID,
        output: String,
    ): Boolean

    /**
     * Transitions to `failed`, stamps `completed_at`, stores [error],
     * clears claim fields. Returns true if the row was updated.
     */
    fun markFailed(
        id: UUID,
        error: String,
    ): Boolean

    /**
     * Transitions to `cancelled`, stamps `completed_at`, clears claim
     * fields. Refuses to transition if the run is already terminal.
     */
    fun markCancelled(id: UUID): Boolean

    /**
     * Updates `heartbeat_at` for the worker owning the run. Returns true if
     * the row was updated (claimed and not terminal), false otherwise.
     */
    fun heartbeat(
        id: UUID,
        claimedBy: String,
    ): Boolean

    /**
     * Lists runs with the given status, ordered by `scheduled_at` ascending.
     * Intended for diagnostics / dashboards — the hot claim path lives in
     * EPIC 3 and uses a SKIP LOCKED query directly.
     */
    fun listByStatus(
        status: RunStatus,
        limit: Int,
    ): List<Run>

    /** All direct children of the given run (one level only). */
    fun listChildren(parentRunId: UUID): List<Run>

    /** Total count across all statuses. */
    fun count(): Long
}
