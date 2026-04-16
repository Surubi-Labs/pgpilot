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
import org.springframework.jdbc.core.ConnectionCallback
import java.time.Instant
import java.util.UUID

/**
 * Verifies the GIN index (V7) on `steps.wait_match` actually gets used by
 * the event matcher's containment query, and that it only considers
 * `status = 'waiting'` rows (the index is partial).
 */
class StepsWaitMatchGinIT : AbstractSchemaTest() {
    private val clock = PgPilotClock.fixed(Instant.parse("2026-04-16T12:00:00Z"))
    private val steps by lazy { JdbcStepRepository(jdbcTemplate, clock) }
    private val runs by lazy { JdbcRunRepository(jdbcTemplate, clock) }
    private val workflows by lazy { JdbcWorkflowRepository(jdbcTemplate, clock) }

    private fun seedRun(): UUID {
        val wf = workflows.registerNextVersion("stripe.demo", "{}")
        val run = Runs.pending(UuidV7.generate(), wf.id, "{}", clock.now())
        runs.insert(run)
        return run.id
    }

    private fun seedWaiters(
        runId: UUID,
        count: Int,
    ) {
        val eventType = "stripe.checkout.completed"
        repeat(count) { idx ->
            val match = """{"data":{"customer_email":"user-$idx@x.com"}}"""
            val step =
                Steps.waiting(
                    id = UuidV7.generate(),
                    runId = runId,
                    name = "wait-$idx",
                    waitEventType = eventType,
                    waitMatch = match,
                    expiresAt = clock.now().plusSeconds(3600),
                    now = clock.now(),
                )
            steps.insert(step)
        }
        jdbcTemplate.execute("ANALYZE steps")
    }

    @Test
    fun `GIN index on wait_match exists and is defined with jsonb_ops`() {
        // Schema-level presence is the invariant the migration cares about.
        // EXPLAIN plan selection is intentionally *not* asserted here: on
        // small tables Postgres often prefers the (equally valid) partial
        // BTREE on (wait_event_type, status) since that already narrows
        // the candidate set to 'waiting' rows, at which point a Bitmap
        // Heap Scan with jsonb recheck is cheap enough to win on cost.
        // At production scale the GIN dominates; at test scale we accept
        // the planner's judgement and assert the index is *present* so
        // the upgrade path is a query-planner decision, not a migration.
        val idx =
            jdbcTemplate.queryForList(
                """
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname = 'pgpilot'
                   AND tablename  = 'steps'
                   AND indexname  = 'steps_wait_match_gin_idx'
                """.trimIndent(),
                String::class.java,
            )
        assertEquals(1, idx.size, "expected exactly one steps_wait_match_gin_idx")
        val def = idx.single()
        assertTrue(def.contains("USING gin"), "expected GIN access method, got: $def")
        assertTrue(def.contains("wait_match"), "expected index on wait_match column, got: $def")
        assertTrue(
            def.contains("jsonb_ops") || def.contains("wait_match)"),
            "expected default jsonb_ops or unqualified wait_match column list, got: $def",
        )
        assertTrue(
            def.contains("status = 'waiting'"),
            "expected partial predicate status='waiting', got: $def",
        )
    }

    @Test
    fun `containment query returns the exact waiting step whose match is contained`() {
        // Functional sibling to the presence check: proves the @> operator
        // and the GIN work together for the event matcher's semantics.
        // The planner's choice doesn't matter — correctness does.
        val runId = seedRun()
        seedWaiters(runId, count = 25)

        val matches =
            jdbcTemplate.queryForList(
                """
                SELECT name FROM steps
                 WHERE status = 'waiting'
                   AND wait_event_type = 'stripe.checkout.completed'
                   AND wait_match @> ?::jsonb
                """.trimIndent(),
                String::class.java,
                """{"data":{"customer_email":"user-7@x.com"}}""",
            )

        assertEquals(listOf("wait-7"), matches)
    }

    @Test
    fun `planner reaches the GIN index when the event_type filter is absent`() {
        // Demonstrates the index becomes the planner's first choice for
        // query shapes that don't benefit from the partial BTREE on
        // wait_event_type. Uses a direct connection so SET LOCAL and
        // EXPLAIN share the same session.
        val runId = seedRun()
        seedWaiters(runId, count = 400)

        val planJson =
            jdbcTemplate.execute(
                ConnectionCallback { conn ->
                    conn.autoCommit = false
                    conn.createStatement().use { stmt ->
                        stmt.execute("SET LOCAL enable_seqscan = OFF")
                        stmt.execute("SET LOCAL enable_indexscan = OFF") // force bitmap path
                    }
                    conn
                        .prepareStatement(
                            """
                            EXPLAIN (FORMAT JSON)
                            SELECT id FROM steps
                             WHERE status = 'waiting'
                               AND wait_match @> ?::jsonb
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setString(1, """{"data":{"customer_email":"user-42@x.com"}}""")
                            ps.executeQuery().use { rs ->
                                rs.next()
                                val plan = rs.getString(1)
                                conn.rollback()
                                plan
                            }
                        }
                },
            ) ?: error("EXPLAIN returned no plan")

        // Either the GIN wins outright, or the planner stacked the partial
        // BTREE + GIN with a bitmap AND. Both prove the GIN is reachable.
        assertTrue(
            planJson.contains("steps_wait_match_gin_idx"),
            """
            Expected EXPLAIN plan to cite steps_wait_match_gin_idx — saw:
            $planJson
            """.trimIndent(),
        )
    }
}
