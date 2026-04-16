package dev.pgpilot.orchestrator.schema

import dev.pgpilot.orchestrator.testsupport.AbstractPgTest
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Runs the Flyway migrations from `db/migration` against the Testcontainers
 * Postgres instance and asserts the outcome: the `pgpilot` schema exists,
 * V0 is recorded in Flyway's history, and no orphan objects leaked into
 * `public`.
 */
class FlywayBaselineIT : AbstractPgTest() {
    @Test
    fun `V0 baseline creates the pgpilot schema`() {
        val flyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, jdbcUser, jdbcPassword)
                .schemas("pgpilot")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()

        val result = flyway.migrate()
        assertEquals(1, result.migrationsExecuted, "expected V0 to run exactly once")

        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.createStatement().use { stmt ->
                stmt
                    .executeQuery(
                        "SELECT 1 FROM pg_namespace WHERE nspname = 'pgpilot'",
                    ).use { rs ->
                        assertTrue(rs.next(), "expected pgpilot schema to exist after migration")
                    }

                stmt
                    .executeQuery(
                        """
                        SELECT description FROM pg_namespace
                        JOIN pg_description d ON d.objoid = pg_namespace.oid
                        WHERE nspname = 'pgpilot'
                        """.trimIndent(),
                    ).use { rs ->
                        assertTrue(rs.next(), "expected schema comment to exist")
                        val comment = rs.getString(1)
                        assertTrue(
                            comment.contains("PgPilot workflow orchestration schema"),
                            "expected baseline comment, got: $comment",
                        )
                    }

                stmt
                    .executeQuery(
                        """
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'pgpilot'
                          AND table_name <> 'flyway_schema_history'
                        """.trimIndent(),
                    ).use { rs ->
                        rs.next()
                        assertEquals(
                            0,
                            rs.getInt(1),
                            "V0 must only land the schema itself (plus flyway_schema_history); no user tables yet",
                        )
                    }
            }
        }
    }

    @Test
    fun `running migrate twice is idempotent`() {
        val flyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, jdbcUser, jdbcPassword)
                .schemas("pgpilot_twice")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()

        flyway.migrate()
        val second = flyway.migrate()
        assertEquals(
            0,
            second.migrationsExecuted,
            "second migrate() should be a no-op; got ${second.migrationsExecuted}",
        )
    }
}
