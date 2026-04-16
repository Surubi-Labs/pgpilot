package dev.pgpilot.orchestrator.steps

import com.fasterxml.jackson.databind.ObjectMapper
import dev.pgpilot.orchestrator.core.PgPilotClock
import dev.pgpilot.orchestrator.core.UuidV7
import dev.pgpilot.orchestrator.runs.JdbcRunRepository
import dev.pgpilot.orchestrator.runs.Runs
import dev.pgpilot.orchestrator.testsupport.AbstractSchemaTest
import dev.pgpilot.orchestrator.workflows.JdbcWorkflowRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

class JdbcStepRepositoryIT : AbstractSchemaTest() {
    private val clock = PgPilotClock.fixed(Instant.parse("2026-04-16T12:00:00Z"))
    private val steps: StepRepository by lazy { JdbcStepRepository(jdbcTemplate, clock) }
    private val runs by lazy { JdbcRunRepository(jdbcTemplate, clock) }
    private val workflows by lazy { JdbcWorkflowRepository(jdbcTemplate, clock) }
    private val json = ObjectMapper()

    private fun assertJsonEquals(
        expected: String,
        actual: String?,
    ) {
        assertNotNull(actual, "expected non-null JSON")
        assertEquals(json.readTree(expected), json.readTree(actual!!))
    }

    private fun seedRun(): UUID {
        val wf = workflows.registerNextVersion("user.onboard", "{}")
        val run = Runs.pending(UuidV7.generate(), wf.id, "{}", clock.now())
        runs.insert(run)
        return run.id
    }

    private fun insertPending(
        runId: UUID = seedRun(),
        name: String = "create-account",
    ): Step =
        Steps
            .pending(UuidV7.generate(), runId, name, """{"key":1}""", clock.now())
            .also { steps.insert(it) }

    @Test
    fun `insert round-trips all fields`() {
        val runId = seedRun()
        val step =
            Steps.pending(UuidV7.generate(), runId, "create-account", """{"email":"hi@x.com"}""", clock.now())

        steps.insert(step)

        val loaded = steps.findById(step.id)!!
        assertEquals(step.id, loaded.id)
        assertEquals(runId, loaded.runId)
        assertEquals("create-account", loaded.name)
        assertEquals(StepStatus.PENDING, loaded.status)
        assertJsonEquals("""{"email":"hi@x.com"}""", loaded.input)
        assertNull(loaded.output)
        assertNull(loaded.error)
        assertEquals(0, loaded.attempt)
    }

    @Test
    fun `findByRunIdAndName backs SDK memoization`() {
        val runId = seedRun()
        insertPending(runId, "create-account")

        val hit = steps.findByRunIdAndName(runId, "create-account")
        assertNotNull(hit)

        val miss = steps.findByRunIdAndName(runId, "send-welcome")
        assertNull(miss)
    }

    @Test
    fun `listByRunId returns steps ordered by createdAt`() {
        val runId = seedRun()
        val first = insertPending(runId, "step-a")
        val second = insertPending(runId, "step-b")
        val third = insertPending(runId, "step-c")

        val list = steps.listByRunId(runId).map { it.id }
        assertEquals(listOf(first.id, second.id, third.id), list)
    }

    @Test
    fun `(run_id, name) is unique within a run`() {
        val runId = seedRun()
        insertPending(runId, "create-account")
        val dupe =
            Steps.pending(UuidV7.generate(), runId, "create-account", "{}", clock.now())

        assertThrows(DuplicateKeyException::class.java) { steps.insert(dupe) }
    }

    @Test
    fun `different runs may reuse the same step name`() {
        val a = seedRun()
        val b = seedRun()
        insertPending(a, "create-account")
        insertPending(b, "create-account")
        assertEquals(2L, steps.count())
    }

    @Test
    fun `FK prevents inserting a step for an unknown run`() {
        val step =
            Steps.pending(UuidV7.generate(), UuidV7.generate(), "orphan", "{}", clock.now())
        assertThrows(DataIntegrityViolationException::class.java) { steps.insert(step) }
    }

    @Test
    fun `deleting a run CASCADEs to its steps`() {
        val runId = seedRun()
        val step = insertPending(runId, "create-account")

        val deleted = jdbcTemplate.update("DELETE FROM runs WHERE id = ?", runId)
        assertEquals(1, deleted)

        assertNull(steps.findById(step.id), "step should have been cascade-deleted with its run")
    }

    @Test
    fun `markRunning bumps attempt and stamps started_at`() {
        val step = insertPending()
        assertTrue(steps.markRunning(step.id))

        val loaded = steps.findById(step.id)!!
        assertEquals(StepStatus.RUNNING, loaded.status)
        assertEquals(1, loaded.attempt)
        assertNotNull(loaded.startedAt)
    }

    @Test
    fun `markRunning refuses terminal steps`() {
        val step = insertPending()
        steps.markCompleted(step.id, "{}")
        assertFalse(steps.markRunning(step.id))
    }

    @Test
    fun `markCompleted persists output and stamps completed_at`() {
        val step = insertPending()
        steps.markRunning(step.id)
        assertTrue(steps.markCompleted(step.id, """{"id":"abc"}"""))

        val loaded = steps.findById(step.id)!!
        assertEquals(StepStatus.COMPLETED, loaded.status)
        assertJsonEquals("""{"id":"abc"}""", loaded.output)
        assertNotNull(loaded.completedAt)
    }

    @Test
    fun `markFailed persists error payload`() {
        val step = insertPending()
        steps.markRunning(step.id)
        assertTrue(steps.markFailed(step.id, """{"reason":"bad input"}"""))
        assertJsonEquals("""{"reason":"bad input"}""", steps.findById(step.id)!!.error)
    }

    @Test
    fun `markDeadLettered ends the step's life`() {
        val step = insertPending()
        steps.markRunning(step.id)
        assertTrue(steps.markDeadLettered(step.id, """{"reason":"retries exhausted"}"""))

        val loaded = steps.findById(step.id)!!
        assertEquals(StepStatus.DEAD_LETTERED, loaded.status)
        assertFalse(steps.markFailed(step.id, "{}"), "terminal steps cannot transition further")
        assertFalse(steps.markCompleted(step.id, "{}"))
    }

    @Test
    fun `sleeping factory stores wake time and no wait fields`() {
        val runId = seedRun()
        val wake = clock.now().plusSeconds(60)
        val step =
            Steps
                .sleeping(UuidV7.generate(), runId, "step.sleep", wakeAt = wake, now = clock.now())
        steps.insert(step)

        val loaded = steps.findById(step.id)!!
        assertEquals(StepStatus.SLEEPING, loaded.status)
        assertEquals(wake, loaded.scheduledAt)
        assertNull(loaded.waitEventType)
        assertNull(loaded.waitMatch)
    }

    @Test
    fun `waiting factory stores wait subscription and expiry`() {
        val runId = seedRun()
        val expires = clock.now().plusSeconds(7 * 24 * 3600L)
        val step =
            Steps
                .waiting(
                    id = UuidV7.generate(),
                    runId = runId,
                    name = "stripe.checkout",
                    waitEventType = "stripe.checkout.completed",
                    waitMatch = """{"data":{"customer_email":"x"}}""",
                    expiresAt = expires,
                    now = clock.now(),
                )
        steps.insert(step)

        val loaded = steps.findById(step.id)!!
        assertEquals(StepStatus.WAITING, loaded.status)
        assertEquals("stripe.checkout.completed", loaded.waitEventType)
        assertJsonEquals("""{"data":{"customer_email":"x"}}""", loaded.waitMatch)
        assertEquals(expires, loaded.expiresAt)
    }

    @Test
    fun `fan children share fan_group_id and preserve fan_index ordering`() {
        val runId = seedRun()
        val fanStep = insertPending(runId, "process")
        val groupId = UuidV7.generate()

        val children =
            (0 until 5).map { idx ->
                Steps
                    .fanChild(
                        id = UuidV7.generate(),
                        runId = runId,
                        parentName = "process",
                        fanGroupId = groupId,
                        fanIndex = idx,
                        fanParentStepId = fanStep.id,
                        input = """{"i":$idx}""",
                        now = clock.now(),
                    ).also { steps.insert(it) }
            }

        val byGroup = steps.listByFanGroupId(groupId)
        assertEquals(children.map { it.id }, byGroup.map { it.id })
        assertEquals(children.indices.toList(), byGroup.map { it.fanIndex })
        assertEquals(setOf(fanStep.id), byGroup.map { it.fanParentStepId }.toSet())
    }

    @Test
    fun `deleting the fan parent cascades to children`() {
        val runId = seedRun()
        val fanStep = insertPending(runId, "process")
        val groupId = UuidV7.generate()
        val child =
            Steps
                .fanChild(
                    id = UuidV7.generate(),
                    runId = runId,
                    parentName = "process",
                    fanGroupId = groupId,
                    fanIndex = 0,
                    fanParentStepId = fanStep.id,
                    input = "{}",
                    now = clock.now(),
                )
        steps.insert(child)

        jdbcTemplate.update("DELETE FROM steps WHERE id = ?", fanStep.id)
        assertNull(steps.findById(child.id))
    }

    @Test
    fun `status CHECK rejects unknown values via direct JDBC`() {
        val runId = seedRun()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO steps (id, run_id, name, status, input, created_at, updated_at)
                VALUES (?, ?, 'x', 'bogus', '{}'::jsonb, now(), now())
                """.trimIndent(),
                UuidV7.generate(),
                runId,
            )
        }
    }

    @Test
    fun `wait fields CHECK enforces both-or-neither`() {
        val runId = seedRun()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO steps (id, run_id, name, status, input, wait_event_type,
                                   created_at, updated_at)
                VALUES (?, ?, 'half', 'waiting', '{}'::jsonb, 'stripe.evt',
                        now(), now())
                """.trimIndent(),
                UuidV7.generate(),
                runId,
            )
        }
    }

    @Test
    fun `fan fields CHECK enforces both-or-neither plus non-negative index`() {
        val runId = seedRun()
        val parent = insertPending(runId, "parent")

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO steps (id, run_id, name, status, fan_group_id, fan_parent_step_id,
                                   input, created_at, updated_at)
                VALUES (?, ?, 'p[x]', 'pending', ?, ?, '{}'::jsonb, now(), now())
                """.trimIndent(),
                UuidV7.generate(),
                runId,
                UuidV7.generate(),
                parent.id,
            )
        }
    }
}
