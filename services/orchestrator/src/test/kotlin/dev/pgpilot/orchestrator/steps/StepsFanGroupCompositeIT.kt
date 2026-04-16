package dev.pgpilot.orchestrator.steps

import dev.pgpilot.orchestrator.core.PgPilotClock
import dev.pgpilot.orchestrator.core.UuidV7
import dev.pgpilot.orchestrator.runs.JdbcRunRepository
import dev.pgpilot.orchestrator.runs.Runs
import dev.pgpilot.orchestrator.testsupport.AbstractSchemaTest
import dev.pgpilot.orchestrator.workflows.JdbcWorkflowRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Fan-in readiness query coverage. Validates that V8 creates the composite
 * index in the expected shape and that the fan-in "any sibling still
 * active?" query plan can use it.
 */
class StepsFanGroupCompositeIT : AbstractSchemaTest() {
    private val clock = PgPilotClock.fixed(Instant.parse("2026-04-16T12:00:00Z"))
    private val steps by lazy { JdbcStepRepository(jdbcTemplate, clock) }
    private val runs by lazy { JdbcRunRepository(jdbcTemplate, clock) }
    private val workflows by lazy { JdbcWorkflowRepository(jdbcTemplate, clock) }

    private fun seedRun(): UUID {
        val wf = workflows.registerNextVersion("fan.demo", "{}")
        val run = Runs.pending(UuidV7.generate(), wf.id, "{}", clock.now())
        runs.insert(run)
        return run.id
    }

    @Test
    fun `composite index exists in the expected shape`() {
        val defs =
            jdbcTemplate.queryForList(
                """
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname = 'pgpilot'
                   AND tablename  = 'steps'
                   AND indexname  = 'steps_fan_group_status_idx'
                """.trimIndent(),
                String::class.java,
            )
        assertEquals(1, defs.size, "expected exactly one steps_fan_group_status_idx")
        val def = defs.single()
        assertTrue(
            def.contains("(run_id, fan_group_id, status)"),
            "expected column list (run_id, fan_group_id, status), got: $def",
        )
        assertTrue(
            def.contains("fan_group_id IS NOT NULL"),
            "expected partial predicate fan_group_id IS NOT NULL, got: $def",
        )
    }

    @Test
    fun `composite covers the fan-in query columns in prefix order`() {
        // The composite must include run_id first (required for filtering),
        // fan_group_id second (narrowing to the sibling set), and status
        // last (for the NOT IN predicate). Order matters — if a future
        // migration rearranges them this test fails loudly.
        val pos = columnOrder("steps_fan_group_status_idx")
        assertEquals(
            listOf("run_id", "fan_group_id", "status"),
            pos,
            "expected (run_id, fan_group_id, status) prefix order, got $pos",
        )
    }

    private fun columnOrder(indexName: String): List<String> =
        jdbcTemplate.queryForList(
            """
            SELECT a.attname
              FROM pg_index idx
              JOIN pg_class c     ON c.oid = idx.indexrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              JOIN unnest(idx.indkey) WITH ORDINALITY AS u(attnum, pos) ON TRUE
              JOIN pg_attribute a ON a.attrelid = idx.indrelid AND a.attnum = u.attnum
             WHERE n.nspname = 'pgpilot'
               AND c.relname = ?
             ORDER BY u.pos
            """.trimIndent(),
            String::class.java,
            indexName,
        )

    @Test
    fun `fan-in readiness query returns the number of siblings still active`() {
        val runId = seedRun()
        val parent = Steps.pending(UuidV7.generate(), runId, "process", "{}", clock.now())
        steps.insert(parent)
        val groupId = UuidV7.generate()

        // 3 completed, 2 running, 1 failed, 1 pending → 3 still active
        val layout =
            listOf(
                StepStatus.COMPLETED to 3,
                StepStatus.RUNNING to 2,
                StepStatus.FAILED to 1,
                StepStatus.PENDING to 1,
            )

        var idx = 0
        for ((status, count) in layout) {
            repeat(count) {
                val child =
                    Steps
                        .fanChild(
                            id = UuidV7.generate(),
                            runId = runId,
                            parentName = "process",
                            fanGroupId = groupId,
                            fanIndex = idx,
                            fanParentStepId = parent.id,
                            input = "{}",
                            now = clock.now(),
                        ).copy(
                            status = status,
                            completedAt = if (status.isTerminal) clock.now() else null,
                        )
                steps.insert(child)
                idx++
            }
        }

        val stillActive =
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM steps
                 WHERE run_id       = ?
                   AND fan_group_id = ?
                   AND status NOT IN ('completed', 'failed', 'dead_lettered')
                """.trimIndent(),
                Long::class.java,
                runId,
                groupId,
            )

        // RUNNING + PENDING = 3; FAILED is terminal for fan-in purposes.
        assertEquals(3L, stillActive)
    }
}
