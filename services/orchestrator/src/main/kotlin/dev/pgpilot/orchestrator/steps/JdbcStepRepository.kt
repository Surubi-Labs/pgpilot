package dev.pgpilot.orchestrator.steps

import dev.pgpilot.orchestrator.core.PgPilotClock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcStepRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: PgPilotClock,
) : StepRepository {
    override fun insert(step: Step): Step {
        jdbcTemplate.update(
            INSERT_SQL,
            step.id,
            step.runId,
            step.name,
            step.status.dbValue,
            step.attempt,
            step.input,
            step.output,
            step.error,
            step.waitEventType,
            step.waitMatch,
            step.scheduledAt?.let { Timestamp.from(it) },
            step.expiresAt?.let { Timestamp.from(it) },
            step.startedAt?.let { Timestamp.from(it) },
            step.completedAt?.let { Timestamp.from(it) },
            step.fanGroupId,
            step.fanIndex,
            step.fanParentStepId,
            Timestamp.from(step.createdAt),
            Timestamp.from(step.updatedAt),
        )
        return step
    }

    override fun findById(id: UUID): Step? =
        jdbcTemplate
            .query("$SELECT_ALL WHERE id = ?", MAPPER, id)
            .singleOrNull()

    override fun findByRunIdAndName(
        runId: UUID,
        name: String,
    ): Step? =
        jdbcTemplate
            .query("$SELECT_ALL WHERE run_id = ? AND name = ?", MAPPER, runId, name)
            .singleOrNull()

    override fun listByRunId(runId: UUID): List<Step> =
        jdbcTemplate.query("$SELECT_ALL WHERE run_id = ? ORDER BY created_at ASC", MAPPER, runId)

    override fun listByFanGroupId(fanGroupId: UUID): List<Step> =
        jdbcTemplate.query(
            "$SELECT_ALL WHERE fan_group_id = ? ORDER BY fan_index ASC NULLS LAST",
            MAPPER,
            fanGroupId,
        )

    override fun markRunning(id: UUID): Boolean {
        val now = Timestamp.from(clock.now())
        val rows =
            jdbcTemplate.update(
                """
                UPDATE steps
                   SET status     = 'running',
                       started_at = COALESCE(started_at, ?),
                       attempt    = attempt + 1,
                       updated_at = ?
                 WHERE id = ?
                   AND status IN ('pending', 'sleeping', 'waiting', 'running')
                """.trimIndent(),
                now,
                now,
                id,
            )
        return rows == 1
    }

    override fun markCompleted(
        id: UUID,
        output: String,
    ): Boolean = markTerminal(id, StepStatus.COMPLETED, output = output)

    override fun markFailed(
        id: UUID,
        error: String,
    ): Boolean = markTerminal(id, StepStatus.FAILED, error = error)

    override fun markDeadLettered(
        id: UUID,
        error: String,
    ): Boolean = markTerminal(id, StepStatus.DEAD_LETTERED, error = error)

    private fun markTerminal(
        id: UUID,
        target: StepStatus,
        output: String? = null,
        error: String? = null,
    ): Boolean {
        require(target.isTerminal) { "$target is not a terminal step status" }
        val now = Timestamp.from(clock.now())
        val rows =
            jdbcTemplate.update(
                """
                UPDATE steps
                   SET status       = ?,
                       output       = COALESCE(?::jsonb, output),
                       error        = COALESCE(?::jsonb, error),
                       completed_at = ?,
                       updated_at   = ?
                 WHERE id = ?
                   AND status NOT IN ('completed', 'failed', 'dead_lettered')
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

    override fun count(): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM steps", Long::class.java) ?: 0L

    private companion object {
        const val SELECT_ALL: String =
            """
            SELECT id, run_id, name, status, attempt,
                   input, output, error,
                   wait_event_type, wait_match,
                   scheduled_at, expires_at, started_at, completed_at,
                   fan_group_id, fan_index, fan_parent_step_id,
                   created_at, updated_at
              FROM steps
            """

        const val INSERT_SQL: String =
            """
            INSERT INTO steps (
                id, run_id, name, status, attempt,
                input, output, error,
                wait_event_type, wait_match,
                scheduled_at, expires_at, started_at, completed_at,
                fan_group_id, fan_index, fan_parent_step_id,
                created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?::jsonb, ?::jsonb, ?::jsonb,
                ?, ?::jsonb,
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?
            )
            """

        val MAPPER: RowMapper<Step> =
            RowMapper { rs, _ ->
                Step(
                    id = rs.getObject("id", UUID::class.java),
                    runId = rs.getObject("run_id", UUID::class.java),
                    name = rs.getString("name"),
                    status = StepStatus.fromDbValue(rs.getString("status")),
                    attempt = rs.getInt("attempt"),
                    input = rs.getString("input") ?: "null",
                    output = rs.getString("output"),
                    error = rs.getString("error"),
                    waitEventType = rs.getString("wait_event_type"),
                    waitMatch = rs.getString("wait_match"),
                    scheduledAt = rs.getTimestamp("scheduled_at")?.toInstant(),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    startedAt = rs.getTimestamp("started_at")?.toInstant(),
                    completedAt = rs.getTimestamp("completed_at")?.toInstant(),
                    fanGroupId = rs.getObject("fan_group_id", UUID::class.java),
                    fanIndex = (rs.getObject("fan_index") as? Number)?.toInt(),
                    fanParentStepId = rs.getObject("fan_parent_step_id", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }
    }
}

/**
 * Factory helpers for constructing [Step] values. Each helper captures the
 * invariants each step type wants preserved (pending vs waiting vs sleeping
 * vs fan child) so tests and the orchestrator don't have to hand-build
 * the 19-field data class every time.
 */
object Steps {
    fun pending(
        id: UUID,
        runId: UUID,
        name: String,
        input: String,
        now: Instant,
    ): Step =
        Step(
            id = id,
            runId = runId,
            name = name,
            status = StepStatus.PENDING,
            attempt = 0,
            input = input,
            output = null,
            error = null,
            waitEventType = null,
            waitMatch = null,
            scheduledAt = null,
            expiresAt = null,
            startedAt = null,
            completedAt = null,
            fanGroupId = null,
            fanIndex = null,
            fanParentStepId = null,
            createdAt = now,
            updatedAt = now,
        )

    fun sleeping(
        id: UUID,
        runId: UUID,
        name: String,
        wakeAt: Instant,
        now: Instant,
    ): Step =
        pending(id, runId, name, "{}", now)
            .copy(status = StepStatus.SLEEPING, scheduledAt = wakeAt)

    fun waiting(
        id: UUID,
        runId: UUID,
        name: String,
        waitEventType: String,
        waitMatch: String,
        expiresAt: Instant?,
        now: Instant,
    ): Step =
        pending(id, runId, name, "{}", now)
            .copy(
                status = StepStatus.WAITING,
                waitEventType = waitEventType,
                waitMatch = waitMatch,
                expiresAt = expiresAt,
            )

    fun fanChild(
        id: UUID,
        runId: UUID,
        parentName: String,
        fanGroupId: UUID,
        fanIndex: Int,
        fanParentStepId: UUID,
        input: String,
        now: Instant,
    ): Step =
        pending(id, runId, "$parentName[$fanIndex]", input, now)
            .copy(
                fanGroupId = fanGroupId,
                fanIndex = fanIndex,
                fanParentStepId = fanParentStepId,
            )
}
