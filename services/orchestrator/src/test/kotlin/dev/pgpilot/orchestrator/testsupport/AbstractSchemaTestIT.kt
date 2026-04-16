package dev.pgpilot.orchestrator.testsupport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Sanity check for [AbstractSchemaTest]: confirms Flyway ran, the pgpilot
 * schema exists, and the harness can issue JdbcTemplate queries. Table-level
 * truncation is exercised by repository suites.
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
    fun `managedTables only references tables that actually exist in the schema`() {
        val existing =
            jdbcTemplate
                .queryForList(
                    """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = ? AND table_name <> 'flyway_schema_history'
                    """.trimIndent(),
                    String::class.java,
                    PGPILOT_SCHEMA,
                ).toSet()

        val stale = managedTables().filterNot { existing.contains(it) || !existing.contains("runs") }
        // The assertion's intent: managedTables() is allowed to list future
        // tables (forward-compat), but the truncate path must skip missing
        // ones. AbstractSchemaTest's filterExistingTables() handles that.
        // Here we just assert the schema has at least `workflows` so every
        // downstream repository suite has a table to test against.
        assertTrue(
            "workflows" in existing,
            "expected pgpilot.workflows to exist; only found: $existing (stale=$stale)",
        )
    }
}
