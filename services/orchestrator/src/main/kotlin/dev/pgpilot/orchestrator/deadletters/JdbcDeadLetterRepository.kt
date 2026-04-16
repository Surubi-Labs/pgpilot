package dev.pgpilot.orchestrator.deadletters

import dev.pgpilot.orchestrator.core.PgPilotClock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class JdbcDeadLetterRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: PgPilotClock,
) : DeadLetterRepository {
    override fun insert(deadLetter: DeadLetter): DeadLetter {
        jdbcTemplate.update(
            """
            INSERT INTO dead_letters (
                id, run_id, step_id, reason, last_error,
                attempts, replayable, created_at, replayed_at
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """.trimIndent(),
            deadLetter.id,
            deadLetter.runId,
            deadLetter.stepId,
            deadLetter.reason,
            deadLetter.lastError,
            deadLetter.attempts,
            deadLetter.replayable,
            Timestamp.from(deadLetter.createdAt),
            deadLetter.replayedAt?.let { Timestamp.from(it) },
        )
        return deadLetter
    }

    override fun findById(id: UUID): DeadLetter? =
        jdbcTemplate
            .query("$SELECT_ALL WHERE id = ?", MAPPER, id)
            .singleOrNull()

    override fun listByRunId(runId: UUID): List<DeadLetter> =
        jdbcTemplate.query(
            "$SELECT_ALL WHERE run_id = ? ORDER BY created_at DESC",
            MAPPER,
            runId,
        )

    override fun listByStepId(stepId: UUID): List<DeadLetter> =
        jdbcTemplate.query(
            "$SELECT_ALL WHERE step_id = ? ORDER BY created_at DESC",
            MAPPER,
            stepId,
        )

    override fun listReplayable(limit: Int): List<DeadLetter> =
        jdbcTemplate.query(
            """
            $SELECT_ALL
             WHERE replayable = TRUE AND replayed_at IS NULL
             ORDER BY created_at DESC
             LIMIT ?
            """.trimIndent(),
            MAPPER,
            limit,
        )

    override fun markReplayed(id: UUID): Boolean {
        val now = Timestamp.from(clock.now())
        val rows =
            jdbcTemplate.update(
                """
                UPDATE dead_letters
                   SET replayed_at = ?
                 WHERE id = ? AND replayed_at IS NULL
                """.trimIndent(),
                now,
                id,
            )
        return rows == 1
    }

    override fun archive(id: UUID): Boolean {
        val rows =
            jdbcTemplate.update(
                """
                UPDATE dead_letters
                   SET replayable = FALSE
                 WHERE id = ? AND replayable = TRUE
                """.trimIndent(),
                id,
            )
        return rows == 1
    }

    override fun count(): Long {
        val n = jdbcTemplate.queryForObject("SELECT count(*) FROM dead_letters", Long::class.java)
        return n ?: 0L
    }

    private companion object {
        const val SELECT_ALL: String =
            """
            SELECT id, run_id, step_id, reason, last_error,
                   attempts, replayable, created_at, replayed_at
              FROM dead_letters
            """

        val MAPPER: RowMapper<DeadLetter> =
            RowMapper { rs, _ ->
                DeadLetter(
                    id = rs.getObject("id", UUID::class.java),
                    runId = rs.getObject("run_id", UUID::class.java),
                    stepId = rs.getObject("step_id", UUID::class.java),
                    reason = rs.getString("reason"),
                    lastError = rs.getString("last_error"),
                    attempts = rs.getInt("attempts"),
                    replayable = rs.getBoolean("replayable"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    replayedAt = rs.getTimestamp("replayed_at")?.toInstant(),
                )
            }
    }
}

/**
 * Common failure categories stamped into `dead_letters.reason`. Kept as
 * string constants rather than an enum so new categories can land
 * without a coordinated deploy.
 */
object DeadLetterReasons {
    const val RETRY_EXHAUSTED: String = "retry_exhausted"
    const val TIMEOUT: String = "timeout"
    const val MANUAL_FAIL: String = "manual_fail"
}
