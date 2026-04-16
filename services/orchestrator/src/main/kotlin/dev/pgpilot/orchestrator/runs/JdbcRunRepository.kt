package dev.pgpilot.orchestrator.runs

import dev.pgpilot.orchestrator.core.PgPilotClock
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcRunRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: PgPilotClock,
) : RunRepository {
    override fun insert(run: Run): Run {
        jdbcTemplate.update(
            INSERT_SQL,
            run.id,
            run.workflowId,
            run.status.dbValue,
            run.idempotencyKey,
            run.input,
            run.output,
            run.error,
            run.parentRunId,
            run.rootRunId,
            run.depth,
            Timestamp.from(run.scheduledAt),
            run.startedAt?.let { Timestamp.from(it) },
            run.completedAt?.let { Timestamp.from(it) },
            run.claimedBy,
            run.claimedAt?.let { Timestamp.from(it) },
            run.heartbeatAt?.let { Timestamp.from(it) },
            run.attempt,
            Timestamp.from(run.createdAt),
            Timestamp.from(run.updatedAt),
        )
        return run
    }

    override fun findById(id: UUID): Run? =
        jdbcTemplate
            .query("$SELECT_ALL WHERE id = ?", MAPPER, id)
            .singleOrNull()

    override fun findByIdempotencyKey(idempotencyKey: String): Run? =
        jdbcTemplate
            .query("$SELECT_ALL WHERE idempotency_key = ?", MAPPER, idempotencyKey)
            .singleOrNull()

    override fun insertIdempotent(run: Run): Run {
        if (run.idempotencyKey == null) return insert(run)

        findByIdempotencyKey(run.idempotencyKey)?.let { return it }
        return try {
            insert(run)
        } catch (_: DuplicateKeyException) {
            // Lost the race — another writer inserted the same key between
            // our lookup and insert. Return whatever they wrote.
            findByIdempotencyKey(run.idempotencyKey)
                ?: error("lost idempotency race yet still cannot find the row (key=${run.idempotencyKey})")
        }
    }

    override fun markRunning(
        id: UUID,
        claimedBy: String,
    ): Boolean {
        val now = Timestamp.from(clock.now())
        val rows =
            jdbcTemplate.update(
                """
                UPDATE runs
                   SET status       = 'running',
                       started_at   = COALESCE(started_at, ?),
                       claimed_by   = ?,
                       claimed_at   = ?,
                       heartbeat_at = ?,
                       attempt      = attempt + 1,
                       updated_at   = ?
                 WHERE id = ?
                   AND status IN ('pending', 'running')
                """.trimIndent(),
                now,
                claimedBy,
                now,
                now,
                now,
                id,
            )
        return rows == 1
    }

    override fun markCompleted(
        id: UUID,
        output: String,
    ): Boolean = markTerminal(id, RunStatus.COMPLETED, output = output)

    override fun markFailed(
        id: UUID,
        error: String,
    ): Boolean = markTerminal(id, RunStatus.FAILED, error = error)

    override fun markCancelled(id: UUID): Boolean = markTerminal(id, RunStatus.CANCELLED)

    private fun markTerminal(
        id: UUID,
        target: RunStatus,
        output: String? = null,
        error: String? = null,
    ): Boolean {
        require(target.isTerminal) { "$target is not a terminal state" }
        val now = Timestamp.from(clock.now())
        val rows =
            jdbcTemplate.update(
                """
                UPDATE runs
                   SET status       = ?,
                       output       = COALESCE(?::jsonb, output),
                       error        = COALESCE(?::jsonb, error),
                       completed_at = ?,
                       claimed_by   = NULL,
                       claimed_at   = NULL,
                       heartbeat_at = NULL,
                       updated_at   = ?
                 WHERE id = ?
                   AND status NOT IN ('completed', 'failed', 'cancelled')
                """.trimIndent(),
                target.dbValue,
                output,
                error,
                now,
                now,
                id,
            )
        return rows == 1
    }

    override fun heartbeat(
        id: UUID,
        claimedBy: String,
    ): Boolean {
        val now = Timestamp.from(clock.now())
        val rows =
            jdbcTemplate.update(
                """
                UPDATE runs
                   SET heartbeat_at = ?,
                       updated_at   = ?
                 WHERE id = ?
                   AND claimed_by = ?
                   AND status IN ('pending', 'running')
                """.trimIndent(),
                now,
                now,
                id,
                claimedBy,
            )
        return rows == 1
    }

    override fun listByStatus(
        status: RunStatus,
        limit: Int,
    ): List<Run> =
        jdbcTemplate.query(
            "$SELECT_ALL WHERE status = ? ORDER BY scheduled_at ASC LIMIT ?",
            MAPPER,
            status.dbValue,
            limit,
        )

    override fun listChildren(parentRunId: UUID): List<Run> =
        jdbcTemplate.query(
            "$SELECT_ALL WHERE parent_run_id = ? ORDER BY created_at ASC",
            MAPPER,
            parentRunId,
        )

    override fun count(): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM runs", Long::class.java) ?: 0L

    private companion object {
        const val SELECT_ALL: String =
            """
            SELECT id, workflow_id, status, idempotency_key, input, output, error,
                   parent_run_id, root_run_id, depth,
                   scheduled_at, started_at, completed_at,
                   claimed_by, claimed_at, heartbeat_at,
                   attempt, created_at, updated_at
              FROM runs
            """

        const val INSERT_SQL: String =
            """
            INSERT INTO runs (
                id, workflow_id, status, idempotency_key, input, output, error,
                parent_run_id, root_run_id, depth,
                scheduled_at, started_at, completed_at,
                claimed_by, claimed_at, heartbeat_at,
                attempt, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?
            )
            """

        val MAPPER: RowMapper<Run> =
            RowMapper { rs, _ ->
                Run(
                    id = rs.getObject("id", UUID::class.java),
                    workflowId = rs.getObject("workflow_id", UUID::class.java),
                    status = RunStatus.fromDbValue(rs.getString("status")),
                    idempotencyKey = rs.getString("idempotency_key"),
                    input = rs.getString("input") ?: "null",
                    output = rs.getString("output"),
                    error = rs.getString("error"),
                    parentRunId = rs.getObject("parent_run_id", UUID::class.java),
                    rootRunId = rs.getObject("root_run_id", UUID::class.java),
                    depth = rs.getInt("depth"),
                    scheduledAt = rs.getTimestamp("scheduled_at").toInstant(),
                    startedAt = rs.getTimestamp("started_at")?.toInstant(),
                    completedAt = rs.getTimestamp("completed_at")?.toInstant(),
                    claimedBy = rs.getString("claimed_by"),
                    claimedAt = rs.getTimestamp("claimed_at")?.toInstant(),
                    heartbeatAt = rs.getTimestamp("heartbeat_at")?.toInstant(),
                    attempt = rs.getInt("attempt"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }
    }
}

/** Factory helpers for building [Run] values in production code + tests. */
object Runs {
    /**
     * Builds a root (top-level) run in the `pending` state, ready to be
     * inserted. `rootRunId` is left null — repository callers should
     * backfill it to equal `id` after insert, or the claim loop can do so.
     *
     * Kept as a plain factory (not bound to [JdbcRunRepository]) so the
     * domain object construction stays trivially unit-testable.
     */
    fun pending(
        id: UUID,
        workflowId: UUID,
        input: String,
        now: Instant,
        scheduledAt: Instant = now,
        idempotencyKey: String? = null,
    ): Run =
        Run(
            id = id,
            workflowId = workflowId,
            status = RunStatus.PENDING,
            idempotencyKey = idempotencyKey,
            input = input,
            output = null,
            error = null,
            parentRunId = null,
            rootRunId = null,
            depth = 0,
            scheduledAt = scheduledAt,
            startedAt = null,
            completedAt = null,
            claimedBy = null,
            claimedAt = null,
            heartbeatAt = null,
            attempt = 0,
            createdAt = now,
            updatedAt = now,
        )

    /**
     * Builds a nested run spawned by `step.invoke(...)`. Caller supplies
     * the parent's `id` and `rootRunId`; depth is parent.depth + 1.
     */
    fun child(
        id: UUID,
        workflowId: UUID,
        input: String,
        now: Instant,
        parent: Run,
    ): Run =
        pending(
            id = id,
            workflowId = workflowId,
            input = input,
            now = now,
        ).copy(
            parentRunId = parent.id,
            rootRunId = parent.rootRunId ?: parent.id,
            depth = parent.depth + 1,
        )
}
