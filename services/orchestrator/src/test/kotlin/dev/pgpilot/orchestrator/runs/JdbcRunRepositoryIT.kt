package dev.pgpilot.orchestrator.runs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.pgpilot.orchestrator.core.PgPilotClock
import dev.pgpilot.orchestrator.core.UuidV7
import dev.pgpilot.orchestrator.testsupport.AbstractSchemaTest
import dev.pgpilot.orchestrator.workflows.JdbcWorkflowRepository
import dev.pgpilot.orchestrator.workflows.WorkflowRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JdbcRunRepositoryIT : AbstractSchemaTest() {
    private val clock = PgPilotClock.fixed(Instant.parse("2026-04-16T12:00:00Z"))
    private val runs: RunRepository by lazy { JdbcRunRepository(jdbcTemplate, clock) }
    private val workflows: WorkflowRepository by lazy { JdbcWorkflowRepository(jdbcTemplate, clock) }
    private val json = ObjectMapper()

    private fun assertJsonEquals(
        expected: String,
        actual: String?,
    ) {
        assertNotNull(actual, "expected non-null JSON")
        assertEquals(json.readTree(expected), json.readTree(actual!!))
    }

    private fun ensureWorkflow(name: String = "user.onboard"): UUID {
        val wf = workflows.registerNextVersion(name, """{"steps":[]}""")
        return wf.id
    }

    @Test
    fun `insert round-trips all fields`() {
        val workflowId = ensureWorkflow()
        val now = clock.now()
        val run =
            Runs
                .pending(
                    id = UuidV7.generate(),
                    workflowId = workflowId,
                    input = """{"email":"hi@x.com"}""",
                    now = now,
                    idempotencyKey = "key-1",
                ).copy(rootRunId = null, depth = 0)

        runs.insert(run)

        val loaded = runs.findById(run.id)
        assertNotNull(loaded)
        assertEquals(run.id, loaded!!.id)
        assertEquals(workflowId, loaded.workflowId)
        assertEquals(RunStatus.PENDING, loaded.status)
        assertEquals("key-1", loaded.idempotencyKey)
        assertJsonEquals("""{"email":"hi@x.com"}""", loaded.input)
        assertNull(loaded.output)
        assertNull(loaded.error)
        assertEquals(0, loaded.depth)
        assertEquals(now, loaded.scheduledAt)
        assertEquals(0, loaded.attempt)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(runs.findById(UuidV7.generate()))
    }

    @Test
    fun `insertIdempotent returns existing row on second call with same key`() {
        val workflowId = ensureWorkflow()
        val first =
            Runs
                .pending(UuidV7.generate(), workflowId, "{}", clock.now(), idempotencyKey = "k")
        val second =
            Runs
                .pending(UuidV7.generate(), workflowId, """{"diff":true}""", clock.now(), idempotencyKey = "k")

        runs.insertIdempotent(first)
        val result = runs.insertIdempotent(second)

        assertEquals(first.id, result.id, "expected dedup to return the first insert")
        assertEquals(1L, runs.count(), "only one row should exist for the idempotency key")
    }

    @Test
    fun `insertIdempotent without a key inserts normally`() {
        val workflowId = ensureWorkflow()
        runs.insertIdempotent(Runs.pending(UuidV7.generate(), workflowId, "{}", clock.now()))
        runs.insertIdempotent(Runs.pending(UuidV7.generate(), workflowId, "{}", clock.now()))
        assertEquals(2L, runs.count())
    }

    @Test
    fun `insertIdempotent survives concurrent duplicates`() {
        val workflowId = ensureWorkflow()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val winners = AtomicInteger()

        try {
            val futures =
                (1..threads).map {
                    pool.submit {
                        val run =
                            Runs.pending(
                                id = UuidV7.generate(),
                                workflowId = workflowId,
                                input = "{}",
                                now = clock.now(),
                                idempotencyKey = "same-key",
                            )
                        val result = runs.insertIdempotent(run)
                        if (result.id == run.id) winners.incrementAndGet()
                    }
                }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        assertEquals(1L, runs.count(), "only one run should have landed")
        assertEquals(1, winners.get(), "exactly one thread should be the winner")
    }

    @Test
    fun `markRunning transitions pending to running and bumps attempt`() {
        val run = insertPending()
        val updated = runs.markRunning(run.id, "worker-1")
        assertTrue(updated)

        val loaded = runs.findById(run.id)!!
        assertEquals(RunStatus.RUNNING, loaded.status)
        assertEquals("worker-1", loaded.claimedBy)
        assertNotNull(loaded.claimedAt)
        assertEquals(1, loaded.attempt)
    }

    @Test
    fun `markRunning is a no-op for terminal runs`() {
        val run = insertPending()
        runs.markRunning(run.id, "worker-1")
        runs.markCompleted(run.id, """{"ok":true}""")

        assertFalse(runs.markRunning(run.id, "worker-2"))
        val loaded = runs.findById(run.id)!!
        assertEquals(RunStatus.COMPLETED, loaded.status)
    }

    @Test
    fun `markCompleted stores output and clears claim fields`() {
        val run = insertPending()
        runs.markRunning(run.id, "worker-1")
        assertTrue(runs.markCompleted(run.id, """{"ok":true}"""))

        val loaded = runs.findById(run.id)!!
        assertEquals(RunStatus.COMPLETED, loaded.status)
        assertJsonEquals("""{"ok":true}""", loaded.output)
        assertNull(loaded.claimedBy)
        assertNull(loaded.claimedAt)
        assertNull(loaded.heartbeatAt)
        assertNotNull(loaded.completedAt)
    }

    @Test
    fun `markFailed stores error payload`() {
        val run = insertPending()
        runs.markRunning(run.id, "worker-1")
        assertTrue(runs.markFailed(run.id, """{"reason":"timeout"}"""))

        val loaded = runs.findById(run.id)!!
        assertEquals(RunStatus.FAILED, loaded.status)
        assertJsonEquals("""{"reason":"timeout"}""", loaded.error)
    }

    @Test
    fun `markCancelled is valid from pending or running`() {
        val pending = insertPending()
        assertTrue(runs.markCancelled(pending.id))
        assertEquals(RunStatus.CANCELLED, runs.findById(pending.id)!!.status)

        val running = insertPending()
        runs.markRunning(running.id, "worker-1")
        assertTrue(runs.markCancelled(running.id))
        assertEquals(RunStatus.CANCELLED, runs.findById(running.id)!!.status)
    }

    @Test
    fun `terminal transitions are idempotent but flag follow-ups as no-op`() {
        val run = insertPending()
        assertTrue(runs.markCompleted(run.id, "{}"))
        assertFalse(runs.markCompleted(run.id, """{"later":true}"""))
        assertFalse(runs.markFailed(run.id, """{"reason":"too late"}"""))
        assertFalse(runs.markCancelled(run.id))
    }

    @Test
    fun `heartbeat requires matching claimed_by and non-terminal status`() {
        val run = insertPending()
        assertFalse(runs.heartbeat(run.id, "worker-1"), "heartbeat before claim should fail")

        runs.markRunning(run.id, "worker-1")
        assertTrue(runs.heartbeat(run.id, "worker-1"))
        assertFalse(runs.heartbeat(run.id, "worker-other"))

        runs.markCompleted(run.id, "{}")
        assertFalse(runs.heartbeat(run.id, "worker-1"), "heartbeat after terminal should fail")
    }

    @Test
    fun `listByStatus returns rows ordered by scheduled_at ascending`() {
        val workflowId = ensureWorkflow()
        val base = clock.now()
        val r1 =
            Runs.pending(UuidV7.generate(), workflowId, "{}", base, scheduledAt = base.plusSeconds(60))
        val r2 = Runs.pending(UuidV7.generate(), workflowId, "{}", base, scheduledAt = base)
        val r3 =
            Runs.pending(UuidV7.generate(), workflowId, "{}", base, scheduledAt = base.plusSeconds(30))
        listOf(r1, r2, r3).forEach { runs.insert(it) }

        val list = runs.listByStatus(RunStatus.PENDING, limit = 10)
        assertEquals(listOf(r2.id, r3.id, r1.id), list.map { it.id })
    }

    @Test
    fun `listChildren returns only direct descendants of parent`() {
        val workflowId = ensureWorkflow()
        val root = insertPending(workflowId)
        val childA =
            Runs
                .child(UuidV7.generate(), workflowId, "{}", clock.now(), parent = root)
                .also { runs.insert(it) }
        val childB =
            Runs
                .child(UuidV7.generate(), workflowId, "{}", clock.now(), parent = root)
                .also { runs.insert(it) }
        // grandchild of root via childA — should NOT show up under root
        Runs
            .child(UuidV7.generate(), workflowId, "{}", clock.now(), parent = childA)
            .also { runs.insert(it) }

        val directChildren = runs.listChildren(root.id).map { it.id }.toSet()
        assertEquals(setOf(childA.id, childB.id), directChildren)
    }

    @Test
    fun `child run tracks rootRunId and depth`() {
        val workflowId = ensureWorkflow()
        val root = insertPending(workflowId)
        val child =
            Runs
                .child(UuidV7.generate(), workflowId, "{}", clock.now(), parent = root)
                .also { runs.insert(it) }
        val grandchild =
            Runs
                .child(UuidV7.generate(), workflowId, "{}", clock.now(), parent = child)
                .also { runs.insert(it) }

        val loadedGrand = runs.findById(grandchild.id)!!
        assertEquals(root.id, loadedGrand.rootRunId)
        assertEquals(child.id, loadedGrand.parentRunId)
        assertEquals(2, loadedGrand.depth)
    }

    @Test
    fun `FK prevents inserting a run for an unknown workflow`() {
        val bogusWorkflow = UuidV7.generate()
        val run = Runs.pending(UuidV7.generate(), bogusWorkflow, "{}", clock.now())

        assertThrows(DataIntegrityViolationException::class.java) {
            runs.insert(run)
        }
    }

    @Test
    fun `status CHECK rejects unknown values via direct JDBC`() {
        val workflowId = ensureWorkflow()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO runs (id, workflow_id, status, input, created_at, updated_at, scheduled_at)
                VALUES (?, ?, 'bogus', '{}'::jsonb, now(), now(), now())
                """.trimIndent(),
                UuidV7.generate(),
                workflowId,
            )
        }
    }

    private fun insertPending(workflowId: UUID = ensureWorkflow()): Run =
        Runs
            .pending(UuidV7.generate(), workflowId, "{}", clock.now())
            .also { runs.insert(it) }
}
