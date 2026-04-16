package dev.pgpilot.orchestrator.schema

import dev.pgpilot.orchestrator.testsupport.AbstractSchemaTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cross-cutting audit of the CHECK constraints and FK cascade policies
 * the plan locked in. Repository tests cover behavior per table; this
 * suite catches a future migration quietly dropping or reshaping an
 * invariant that the plan depends on.
 */
class SchemaConstraintsIT : AbstractSchemaTest() {
    private data class ForeignKey(
        val table: String,
        val constraint: String,
        val references: String,
        val deleteAction: String,
    )

    private fun foreignKeys(): List<ForeignKey> =
        jdbcTemplate
            .query(
                """
                SELECT c.conname       AS constraint_name,
                       tr.relname      AS table_name,
                       ref.relname     AS ref_table,
                       c.confdeltype   AS delete_action
                  FROM pg_constraint c
                  JOIN pg_class      tr  ON tr.oid  = c.conrelid
                  JOIN pg_class      ref ON ref.oid = c.confrelid
                  JOIN pg_namespace  n   ON n.oid   = tr.relnamespace
                 WHERE c.contype = 'f'
                   AND n.nspname = 'pgpilot'
                 ORDER BY tr.relname, c.conname
                """.trimIndent(),
            ) { rs, _ ->
                ForeignKey(
                    table = rs.getString("table_name"),
                    constraint = rs.getString("constraint_name"),
                    references = rs.getString("ref_table"),
                    deleteAction =
                        when (rs.getString("delete_action")) {
                            "a" -> "NO_ACTION"
                            "r" -> "RESTRICT"
                            "c" -> "CASCADE"
                            "n" -> "SET_NULL"
                            "d" -> "SET_DEFAULT"
                            else -> error("unknown confdeltype: ${rs.getString("delete_action")}")
                        },
                )
            }

    private fun checkConstraints(table: String): Map<String, String> =
        jdbcTemplate
            .query(
                """
                SELECT c.conname       AS constraint_name,
                       pg_get_constraintdef(c.oid, true) AS definition
                  FROM pg_constraint c
                  JOIN pg_class      tr ON tr.oid  = c.conrelid
                  JOIN pg_namespace  n  ON n.oid   = tr.relnamespace
                 WHERE c.contype = 'c'
                   AND n.nspname = 'pgpilot'
                   AND tr.relname = ?
                """.trimIndent(),
                { rs, _ -> rs.getString("constraint_name") to rs.getString("definition") },
                table,
            ).toMap()

    @Test
    fun `FK cascade policies match the plan's locked policy table`() {
        val fks = foreignKeys().associateBy { it.constraint }

        val expected =
            listOf(
                // runs
                ForeignKey("runs", "runs_workflow_fk", "workflows", "RESTRICT"),
                ForeignKey("runs", "runs_parent_fk", "runs", "RESTRICT"),
                ForeignKey("runs", "runs_root_fk", "runs", "RESTRICT"),
                // steps
                ForeignKey("steps", "steps_run_fk", "runs", "CASCADE"),
                ForeignKey("steps", "steps_fan_parent_fk", "steps", "CASCADE"),
                // dead_letters
                ForeignKey("dead_letters", "dead_letters_run_fk", "runs", "RESTRICT"),
                ForeignKey("dead_letters", "dead_letters_step_fk", "steps", "RESTRICT"),
            )

        val missing = expected.map { it.constraint } - fks.keys
        assertTrue(missing.isEmpty(), "missing foreign-key constraints: $missing")

        for (want in expected) {
            val got = fks.getValue(want.constraint)
            assertEquals(want.table, got.table, "${want.constraint}.table")
            assertEquals(want.references, got.references, "${want.constraint}.references")
            assertEquals(
                want.deleteAction,
                got.deleteAction,
                "${want.constraint}.deleteAction",
            )
        }
    }

    @Test
    fun `runs status CHECK lists exactly the five locked states`() {
        val def =
            checkConstraints("runs")["runs_status_values"]
                ?: error("runs_status_values CHECK missing")
        for (state in listOf("pending", "running", "completed", "failed", "cancelled")) {
            assertTrue(def.contains("'$state'"), "expected runs CHECK to include '$state': $def")
        }
    }

    @Test
    fun `steps status CHECK lists exactly the seven locked states`() {
        val def =
            checkConstraints("steps")["steps_status_values"]
                ?: error("steps_status_values CHECK missing")
        for (state in listOf(
            "pending",
            "running",
            "completed",
            "failed",
            "waiting",
            "sleeping",
            "dead_lettered",
        )) {
            assertTrue(def.contains("'$state'"), "expected steps CHECK to include '$state': $def")
        }
    }

    @Test
    fun `audit_log actor_type CHECK lists the four locked actor kinds`() {
        val def =
            checkConstraints("audit_log")["audit_log_actor_type_values"]
                ?: error("audit_log_actor_type_values CHECK missing")
        for (kind in listOf("user", "api_key", "system", "worker")) {
            assertTrue(def.contains("'$kind'"), "expected audit_log CHECK to include '$kind': $def")
        }
    }

    @Test
    fun `structural CHECK invariants are present on every table`() {
        assertTrue("workflows_name_not_empty" in checkConstraints("workflows"))
        assertTrue("workflows_version_positive" in checkConstraints("workflows"))

        assertTrue("runs_depth_non_negative" in checkConstraints("runs"))
        assertTrue("runs_attempt_non_negative" in checkConstraints("runs"))
        assertTrue("runs_claim_fields_match" in checkConstraints("runs"))
        assertTrue("runs_completed_at_set_for_terminal" in checkConstraints("runs"))

        assertTrue("steps_name_not_empty" in checkConstraints("steps"))
        assertTrue("steps_attempt_non_negative" in checkConstraints("steps"))
        assertTrue("steps_fan_fields_paired" in checkConstraints("steps"))
        assertTrue("steps_wait_fields_paired" in checkConstraints("steps"))

        assertTrue("events_type_not_empty" in checkConstraints("events"))
        assertTrue("events_source_not_empty" in checkConstraints("events"))

        assertTrue("dead_letters_reason_not_empty" in checkConstraints("dead_letters"))
        assertTrue("dead_letters_attempts_non_negative" in checkConstraints("dead_letters"))
        assertTrue("dead_letters_linked" in checkConstraints("dead_letters"))

        assertTrue("audit_log_action_not_empty" in checkConstraints("audit_log"))
        assertTrue("audit_log_subject_type_not_empty" in checkConstraints("audit_log"))
    }
}
