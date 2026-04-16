package dev.pgpilot.orchestrator.workflows

import dev.pgpilot.orchestrator.core.PgPilotClock
import dev.pgpilot.orchestrator.core.UuidV7
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

/**
 * JdbcTemplate-backed implementation of [WorkflowRepository].
 *
 * SQL is hand-written on purpose: PgPilot leans heavily on Postgres-native
 * primitives (SKIP LOCKED, advisory locks, LISTEN/NOTIFY) where ORMs get
 * in the way. JdbcTemplate keeps queries visible and tunable while staying
 * Spring-idiomatic.
 */
@Repository
class JdbcWorkflowRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: PgPilotClock,
) : WorkflowRepository {
    override fun insert(workflow: Workflow): Workflow {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO pgpilot.workflows (id, name, version, definition, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """.trimIndent(),
                workflow.id,
                workflow.name,
                workflow.version,
                workflow.definition,
                Timestamp.from(workflow.createdAt),
            )
        } catch (e: DuplicateKeyException) {
            throw WorkflowAlreadyExistsException(workflow.name, workflow.version, e)
        }
        return workflow
    }

    override fun findById(id: UUID): Workflow? =
        jdbcTemplate
            .query(
                "SELECT id, name, version, definition, created_at FROM pgpilot.workflows WHERE id = ?",
                MAPPER,
                id,
            ).singleOrNull()

    override fun findLatestByName(name: String): Workflow? =
        jdbcTemplate
            .query(
                """
                SELECT id, name, version, definition, created_at
                FROM pgpilot.workflows
                WHERE name = ?
                ORDER BY version DESC
                LIMIT 1
                """.trimIndent(),
                MAPPER,
                name,
            ).singleOrNull()

    override fun findByNameAndVersion(
        name: String,
        version: Int,
    ): Workflow? =
        jdbcTemplate
            .query(
                """
                SELECT id, name, version, definition, created_at
                FROM pgpilot.workflows
                WHERE name = ? AND version = ?
                """.trimIndent(),
                MAPPER,
                name,
                version,
            ).singleOrNull()

    override fun registerNextVersion(
        name: String,
        definition: String,
    ): Workflow {
        // Loop so concurrent writers both eventually succeed: the loser of
        // the unique-constraint race re-reads the latest version and retries.
        while (true) {
            val latest = findLatestByName(name)
            val nextVersion = (latest?.version ?: 0) + 1
            val candidate =
                Workflow(
                    id = UuidV7.generate(),
                    name = name,
                    version = nextVersion,
                    definition = definition,
                    createdAt = clock.now(),
                )
            try {
                return insert(candidate)
            } catch (_: WorkflowAlreadyExistsException) {
                // Someone else raced us; retry with a fresh latest.
                continue
            }
        }
    }

    override fun count(): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgpilot.workflows",
            Long::class.java,
        ) ?: 0L

    private companion object {
        // rs.getString() on a jsonb column returns the JSON text as-is
        // (the PG driver handles the type conversion without forcing us
        // to import org.postgresql.util.PGobject at compile time).
        val MAPPER: RowMapper<Workflow> =
            RowMapper { rs, _ ->
                Workflow(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    version = rs.getInt("version"),
                    definition = rs.getString("definition") ?: "null",
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }
    }
}

/**
 * Thrown when [WorkflowRepository.insert] hits the `(name, version)` unique
 * constraint. The `registerNextVersion` path catches this internally to
 * handle concurrent-writer races; callers using `insert` directly need to
 * handle it if they care about the race.
 */
class WorkflowAlreadyExistsException(
    val name: String,
    val version: Int,
    cause: Throwable? = null,
) : RuntimeException("workflow '$name' version $version already exists", cause)
