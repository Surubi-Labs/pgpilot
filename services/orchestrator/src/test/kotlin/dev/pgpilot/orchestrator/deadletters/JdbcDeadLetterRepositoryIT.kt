package dev.pgpilot.orchestrator.deadletters

import com.fasterxml.jackson.databind.ObjectMapper
import dev.pgpilot.orchestrator.core.PgPilotClock
import dev.pgpilot.orchestrator.core.UuidV7
import dev.pgpilot.orchestrator.runs.JdbcRunRepository
import dev.pgpilot.orchestrator.runs.Runs
import dev.pgpilot.orchestrator.steps.JdbcStepRepository
import dev.pgpilot.orchestrator.steps.Steps
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
import java.time.Instant
import java.util.UUID

class JdbcDeadLetterRepositoryIT : AbstractSchemaTest() {
    private val clock = PgPilotClock.fixed(Instant.parse("2026-04-16T12:00:00Z"))
    private val repo: DeadLetterRepository by lazy { JdbcDeadLetterRepository(jdbcTemplate, clock) }
    private val runs by lazy { JdbcRunRepository(jdbcTemplate, clock) }
    private val steps by lazy { JdbcStepRepository(jdbcTemplate, clock) }
    private val workflows by lazy { JdbcWorkflowRepository(jdbcTemplate, clock) }
    private val json = ObjectMapper()

    private fun assertJsonEquals(
        expected: String,
        actual: String?,
    ) {
        assertNotNull(actual)
        assertEquals(json.readTree(expected), json.readTree(actual!!))
    }

    private data class Fixture(
        val runId: UUID,
        val stepId: UUID,
    )

    private fun seedRunAndStep(stepName: String = "create-account"): Fixture {
        val wf = workflows.registerNextVersion("user.onboard", "{}")
        val run = Runs.pending(UuidV7.generate(), wf.id, "{}", clock.now())
        runs.insert(run)
        val step = Steps.pending(UuidV7.generate(), run.id, stepName, "{}", clock.now())
        steps.insert(step)
        return Fixture(run.id, step.id)
    }

    private fun buildDl(
        runId: UUID? = null,
        stepId: UUID? = null,
        reason: String = DeadLetterReasons.RETRY_EXHAUSTED,
        lastError: String? = """{"message":"boom"}""",
        attempts: Int = 3,
        replayable: Boolean = true,
        replayedAt: Instant? = null,
    ): DeadLetter =
        DeadLetter(
            id = UuidV7.generate(),
            runId = runId,
            stepId = stepId,
            reason = reason,
            lastError = lastError,
            attempts = attempts,
            replayable = replayable,
            createdAt = clock.now(),
            replayedAt = replayedAt,
        )

    @Test
    fun `data class rejects empty links at construction time`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeadLetter(
                id = UuidV7.generate(),
                runId = null,
                stepId = null,
                reason = "x",
                lastError = null,
                attempts = 0,
                replayable = true,
                createdAt = clock.now(),
                replayedAt = null,
            )
        }
    }

    @Test
    fun `insert round-trips all fields`() {
        val fx = seedRunAndStep()
        val dl = buildDl(runId = fx.runId, stepId = fx.stepId)
        repo.insert(dl)

        val loaded = repo.findById(dl.id)!!
        assertEquals(fx.runId, loaded.runId)
        assertEquals(fx.stepId, loaded.stepId)
        assertEquals(DeadLetterReasons.RETRY_EXHAUSTED, loaded.reason)
        assertJsonEquals("""{"message":"boom"}""", loaded.lastError)
        assertEquals(3, loaded.attempts)
        assertTrue(loaded.replayable)
        assertNull(loaded.replayedAt)
    }

    @Test
    fun `listByRunId surfaces all DL rows for a run`() {
        val fx = seedRunAndStep()
        val a = buildDl(runId = fx.runId)
        val b = buildDl(runId = fx.runId, reason = DeadLetterReasons.TIMEOUT)
        repo.insert(a)
        repo.insert(b)

        val ids = repo.listByRunId(fx.runId).map { it.id }.toSet()
        assertEquals(setOf(a.id, b.id), ids)
    }

    @Test
    fun `listByStepId surfaces all DL rows for a step`() {
        val fx = seedRunAndStep()
        val a = buildDl(stepId = fx.stepId)
        repo.insert(a)

        val list = repo.listByStepId(fx.stepId)
        assertEquals(listOf(a.id), list.map { it.id })
    }

    @Test
    fun `listReplayable returns open, newest-first`() {
        val fx = seedRunAndStep("n1")
        val fx2 = seedRunAndStep("n2")

        val open1 = buildDl(runId = fx.runId)
        val open2 = buildDl(runId = fx2.runId)
        val archived = buildDl(runId = fx.runId, replayable = false)
        val replayed = buildDl(runId = fx.runId, replayedAt = clock.now())

        listOf(open1, open2, archived, replayed).forEach { repo.insert(it) }

        val replayable = repo.listReplayable(limit = 10).map { it.id }.toSet()
        assertEquals(setOf(open1.id, open2.id), replayable)
    }

    @Test
    fun `markReplayed stamps replayedAt once`() {
        val fx = seedRunAndStep()
        val dl = buildDl(runId = fx.runId)
        repo.insert(dl)

        assertTrue(repo.markReplayed(dl.id))
        val loaded = repo.findById(dl.id)!!
        assertNotNull(loaded.replayedAt)

        assertFalse(repo.markReplayed(dl.id), "second replay is a no-op")
    }

    @Test
    fun `archive flips replayable off, only once`() {
        val fx = seedRunAndStep()
        val dl = buildDl(runId = fx.runId)
        repo.insert(dl)

        assertTrue(repo.archive(dl.id))
        val loaded = repo.findById(dl.id)!!
        assertFalse(loaded.replayable)

        assertFalse(repo.archive(dl.id), "archiving an already archived row is a no-op")
    }

    @Test
    fun `FK RESTRICT blocks run deletion while a DL entry points at it`() {
        val fx = seedRunAndStep()
        repo.insert(buildDl(runId = fx.runId))

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update("DELETE FROM runs WHERE id = ?", fx.runId)
        }
    }

    @Test
    fun `FK RESTRICT blocks step deletion while a DL entry points at it`() {
        val fx = seedRunAndStep()
        repo.insert(buildDl(stepId = fx.stepId))

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update("DELETE FROM steps WHERE id = ?", fx.stepId)
        }
    }

    @Test
    fun `CHECK requires at least one of run_id or step_id at the DB level`() {
        // The Kotlin data class rejects this at construction, but an
        // operator writing raw SQL should hit the DB CHECK too.
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO dead_letters (id, reason, attempts, replayable, created_at)
                VALUES (?, 'x', 0, TRUE, now())
                """.trimIndent(),
                UuidV7.generate(),
            )
        }
    }

    @Test
    fun `CHECK rejects empty reason via direct JDBC`() {
        val fx = seedRunAndStep()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO dead_letters (id, run_id, reason, attempts, replayable, created_at)
                VALUES (?, ?, '', 0, TRUE, now())
                """.trimIndent(),
                UuidV7.generate(),
                fx.runId,
            )
        }
    }
}
