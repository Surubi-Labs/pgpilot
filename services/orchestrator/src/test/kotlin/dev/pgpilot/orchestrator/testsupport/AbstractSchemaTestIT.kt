package dev.pgpilot.orchestrator.testsupport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Sanity check for [AbstractSchemaTest]: confirms Flyway ran, the pgpilot
 * schema exists, and the test harness can issue JdbcTemplate queries. The
 * table-level truncation path is exercised by later repository suites.
 */
class AbstractSchemaTestIT : AbstractSchemaTest() {
    @Test
    fun `flyway migrated the pgpilot schema`() {
        val count =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_namespace WHERE nspname = 'pgpilot'",
                Int::class.java,
            )
        assertEquals(1, count, "expected pgpilot schema to exist after Flyway migrate")
    }

    @Test
    fun `pgpilot schema is empty of user tables until V1 runs`() {
        val tables =
            jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'pgpilot'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            )
        assertTrue(
            tables.isEmpty(),
            "V0 baseline should not leak tables; got $tables",
        )
    }
}
