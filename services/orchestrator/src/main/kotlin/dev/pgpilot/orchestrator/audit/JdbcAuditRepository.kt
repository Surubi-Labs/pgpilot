package dev.pgpilot.orchestrator.audit

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class JdbcAuditRepository(
    private val jdbcTemplate: JdbcTemplate,
) : AuditRepository {
    override fun record(entry: AuditEntry): AuditEntry {
        jdbcTemplate.update(
            """
            INSERT INTO audit_log (
                id, actor_type, actor_id, actor_name,
                action, subject_type, subject_id,
                before, after, metadata, ip, user_agent, at
            ) VALUES (
                ?, ?, ?, ?,
                ?, ?, ?,
                ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?
            )
            """.trimIndent(),
            entry.id,
            entry.actorType.dbValue,
            entry.actorId,
            entry.actorName,
            entry.action,
            entry.subjectType,
            entry.subjectId,
            entry.before,
            entry.after,
            entry.metadata,
            entry.ip,
            entry.userAgent,
            Timestamp.from(entry.at),
        )
        return entry
    }

    override fun findById(id: UUID): AuditEntry? =
        jdbcTemplate
            .query("$SELECT_ALL WHERE id = ?", MAPPER, id)
            .singleOrNull()

    override fun listBySubject(
        subjectType: String,
        subjectId: UUID,
        limit: Int,
    ): List<AuditEntry> =
        jdbcTemplate.query(
            """
            $SELECT_ALL
             WHERE subject_type = ? AND subject_id = ?
             ORDER BY at DESC
             LIMIT ?
            """.trimIndent(),
            MAPPER,
            subjectType,
            subjectId,
            limit,
        )

    override fun listByActor(
        actorType: ActorType,
        actorId: String,
        limit: Int,
    ): List<AuditEntry> =
        jdbcTemplate.query(
            """
            $SELECT_ALL
             WHERE actor_type = ? AND actor_id = ?
             ORDER BY at DESC
             LIMIT ?
            """.trimIndent(),
            MAPPER,
            actorType.dbValue,
            actorId,
            limit,
        )

    override fun listRecent(limit: Int): List<AuditEntry> {
        val sql = "$SELECT_ALL ORDER BY at DESC LIMIT ?"
        return jdbcTemplate.query(sql, MAPPER, limit)
    }

    override fun count(): Long {
        val n = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Long::class.java)
        return n ?: 0L
    }

    private companion object {
        const val SELECT_ALL: String =
            """
            SELECT id, actor_type, actor_id, actor_name,
                   action, subject_type, subject_id,
                   before, after, metadata, ip, user_agent, at
              FROM audit_log
            """

        val MAPPER: RowMapper<AuditEntry> =
            RowMapper { rs, _ ->
                AuditEntry(
                    id = rs.getObject("id", UUID::class.java),
                    actorType = ActorType.fromDbValue(rs.getString("actor_type")),
                    actorId = rs.getString("actor_id"),
                    actorName = rs.getString("actor_name"),
                    action = rs.getString("action"),
                    subjectType = rs.getString("subject_type"),
                    subjectId = rs.getObject("subject_id", UUID::class.java),
                    before = rs.getString("before"),
                    after = rs.getString("after"),
                    metadata = rs.getString("metadata") ?: "{}",
                    ip = rs.getString("ip"),
                    userAgent = rs.getString("user_agent"),
                    at = rs.getTimestamp("at").toInstant(),
                )
            }
    }
}

/** Common subject-type strings. Plain constants — new values can land freely. */
object AuditSubjects {
    const val WORKFLOW: String = "workflow"
    const val RUN: String = "run"
    const val STEP: String = "step"
    const val EVENT: String = "event"
    const val DEAD_LETTER: String = "dead_letter"
}
