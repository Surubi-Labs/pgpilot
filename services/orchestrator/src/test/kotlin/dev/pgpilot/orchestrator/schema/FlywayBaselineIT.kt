package dev.pgpilot.orchestrator.schema

import dev.pgpilot.orchestrator.testsupport.AbstractPgTest
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Runs the full Flyway migration chain against a fresh schema on the shared
 * Testcontainers Postgres and asserts baseline invariants that every migration
 * must uphold:
 *
 * - the target schema is created,
 * - the schema comment from V0 survives,
 * - re-running migrate() is a no-op (idempotent).
 *
 * Each test uses its own schema name so parallel / interleaved suites don't
 * observe each other's state.
 */
class FlywayBaselineIT : AbstractPgTest() {
    @Test
    fun `migrate creates the pgpilot schema and preserves the baseline comment`() {
        val schema = "flyway_baseline_${System.nanoTime()}"
        val flyway = flywayFor(schema)

        flyway.migrate()

        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.createStatement().use { stmt ->
                stmt
                    .executeQuery("SELECT 1 FROM pg_namespace WHERE nspname = '$schema'")
                    .use { rs ->
                        assertTrue(rs.next(), "expected $schema schema to exist")
                    }

                stmt
                    .executeQuery(
                        """
                        SELECT description FROM pg_namespace
                        JOIN pg_description d ON d.objoid = pg_namespace.oid
                        WHERE nspname = '$schema'
                        """.trimIndent(),
                    ).use { rs ->
                        assertTrue(rs.next(), "expected schema comment to exist")
                        val comment = rs.getString(1)
                        assertTrue(
                            comment.contains("PgPilot workflow orchestration schema"),
                            "expected baseline comment, got: $comment",
                        )
                    }
            }
        }
    }

    @Test
    fun `running migrate twice is a no-op`() {
        val schema = "flyway_idempotent_${System.nanoTime()}"
        val flyway = flywayFor(schema)

        val first = flyway.migrate()
        val second = flyway.migrate()

        assertTrue(
            first.migrationsExecuted > 0,
            "first migrate should apply at least V0; got ${first.migrationsExecuted}",
        )
        assertEquals(
            0,
            second.migrationsExecuted,
            "second migrate() should be a no-op; got ${second.migrationsExecuted}",
        )
    }

    private fun flywayFor(schema: String): Flyway =
        Flyway
            .configure()
            .dataSource(jdbcUrl, jdbcUser, jdbcPassword)
            .schemas(schema)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .load()
}
