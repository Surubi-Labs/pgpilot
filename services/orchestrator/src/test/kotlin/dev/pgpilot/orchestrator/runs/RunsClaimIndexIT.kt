package dev.pgpilot.orchestrator.runs

import dev.pgpilot.orchestrator.core.PgPilotClock
import dev.pgpilot.orchestrator.core.UuidV7
import dev.pgpilot.orchestrator.testsupport.AbstractSchemaTest
import dev.pgpilot.orchestrator.workflows.JdbcWorkflowRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Verifies that the orchestrator's claim query hits `runs_claim_idx`
 * rather than falling back to a sequential scan.
 *
 * The claim query (EPIC 3 STORY 3.1.1) is:
 *   SELECT id FROM runs
 *    WHERE status = 'pending' AND scheduled_at <= now()
 *    ORDER BY scheduled_at
 *    FOR UPDATE SKIP LOCKED
 *    LIMIT 1;
 *
 * Seeds ~200 rows across all statuses so the planner has a reason to
 * prefer the index, then parses EXPLAIN (FORMAT JSON) to confirm it
 * did. EXPLAIN output is more stable across PG versions than the
 * text form.
 */
class RunsClaimIndexIT : AbstractSchemaTest() {
    private val clock = PgPilotClock.fixed(Instant.parse("2026-04-16T12:00:00Z"))
    private val runs by lazy { JdbcRunRepository(jdbcTemplate, clock) }
    private val workflows by lazy { JdbcWorkflowRepository(jdbcTemplate, clock) }

    private fun seedWorkflow(): UUID = workflows.registerNextVersion("stress.run", "{}").id

    private fun seed(count: Int) {
        val workflowId = seedWorkflow()
        val statuses = RunStatus.entries
        val base = Instant.parse("2026-04-16T11:00:00Z")
        val rng = Random(42)

        repeat(count) { idx ->
            val status = statuses[idx % statuses.size]
            val scheduledAt = base.plusSeconds(rng.nextLong(3600))
            val base =
                Runs.pending(UuidV7.generate(), workflowId, "{}", clock.now(), scheduledAt = scheduledAt)
            val run =
                base.copy(
                    status = status,
                    completedAt = if (status.isTerminal) clock.now() else null,
                )
            runs.insert(run)
        }

        // `ANALYZE` so the planner has fresh row-count estimates.
        jdbcTemplate.execute("ANALYZE runs")
    }

    @Test
    fun `claim query uses runs_claim_idx`() {
        seed(count = 200)

        val planJson =
            jdbcTemplate.queryForObject(
                """
                EXPLAIN (FORMAT JSON)
                SELECT id FROM runs
                 WHERE status = 'pending' AND scheduled_at <= now()
                 ORDER BY scheduled_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
                """.trimIndent(),
                String::class.java,
            ) ?: error("EXPLAIN returned no plan")

        assertTrue(
            planJson.contains("runs_claim_idx"),
            """
            Expected EXPLAIN plan to mention runs_claim_idx — saw:
            $planJson
            """.trimIndent(),
        )
    }

    @Test
    fun `claim query avoids full sequential scan on the pending slice`() {
        seed(count = 200)

        val planJson =
            jdbcTemplate.queryForObject(
                """
                EXPLAIN (FORMAT JSON, ANALYZE TRUE, BUFFERS FALSE)
                SELECT id FROM runs
                 WHERE status = 'pending' AND scheduled_at <= now()
                 ORDER BY scheduled_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
                """.trimIndent(),
                String::class.java,
            ) ?: error("EXPLAIN returned no plan")

        // Guard against planner regressions. A `Seq Scan on runs` would
        // mean the claim index is inert; the partial index should take
        // over well before we reach 200 rows.
        assertTrue(
            !planJson.contains("\"Node Type\": \"Seq Scan\"") ||
                planJson.contains("runs_claim_idx"),
            """
            Expected EXPLAIN plan NOT to be a Seq Scan on runs — saw:
            $planJson
            """.trimIndent(),
        )
    }
}
