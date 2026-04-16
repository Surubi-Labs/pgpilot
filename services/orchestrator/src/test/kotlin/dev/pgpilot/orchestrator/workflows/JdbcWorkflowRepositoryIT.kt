package dev.pgpilot.orchestrator.workflows

import com.fasterxml.jackson.databind.ObjectMapper
import dev.pgpilot.orchestrator.core.PgPilotClock
import dev.pgpilot.orchestrator.core.UuidV7
import dev.pgpilot.orchestrator.testsupport.AbstractSchemaTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JdbcWorkflowRepositoryIT : AbstractSchemaTest() {
    private val clock = PgPilotClock.fixed(Instant.parse("2026-04-16T12:00:00Z"))
    private val repo: WorkflowRepository by lazy { JdbcWorkflowRepository(jdbcTemplate, clock) }
    private val json = ObjectMapper()

    /** Postgres jsonb normalizes whitespace; compare by parsed tree, not by raw string. */
    private fun assertJsonEquals(
        expected: String,
        actual: String,
    ) {
        assertEquals(json.readTree(expected), json.readTree(actual))
    }

    @Test
    fun `insert persists the row and findById returns an equivalent workflow`() {
        val workflow =
            Workflow(
                id = UuidV7.generate(),
                name = "user.onboard",
                version = 1,
                definition = """{"steps":["create-account","send-welcome"]}""",
                createdAt = clock.now(),
            )

        repo.insert(workflow)

        val loaded = repo.findById(workflow.id)
        assertNotNull(loaded)
        assertEquals(workflow.name, loaded!!.name)
        assertEquals(workflow.version, loaded.version)
        assertJsonEquals(workflow.definition, loaded.definition)
        assertEquals(workflow.createdAt, loaded.createdAt)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(repo.findById(UuidV7.generate()))
    }

    @Test
    fun `registerNextVersion seeds v1 on first call`() {
        val w = repo.registerNextVersion("user.onboard", """{"v":1}""")
        assertEquals(1, w.version)
        assertEquals(1, repo.findLatestByName("user.onboard")!!.version)
    }

    @Test
    fun `registerNextVersion increments sequentially`() {
        repo.registerNextVersion("user.onboard", """{"v":1}""")
        val v2 = repo.registerNextVersion("user.onboard", """{"v":2}""")
        val v3 = repo.registerNextVersion("user.onboard", """{"v":3}""")

        assertEquals(2, v2.version)
        assertEquals(3, v3.version)
        assertNotEquals(v2.id, v3.id)
    }

    @Test
    fun `registerNextVersion keeps names independent`() {
        repo.registerNextVersion("user.onboard", "{}")
        repo.registerNextVersion("user.onboard", "{}")
        val billingV1 = repo.registerNextVersion("payment.capture", "{}")

        assertEquals(1, billingV1.version)
        assertEquals(2, repo.findLatestByName("user.onboard")!!.version)
    }

    @Test
    fun `insert rejects duplicate name + version with WorkflowAlreadyExistsException`() {
        val original = repo.registerNextVersion("user.onboard", "{}")
        val collision =
            original.copy(
                id = UuidV7.generate(),
                definition = """{"tampered":true}""",
            )

        val thrown =
            assertThrows(WorkflowAlreadyExistsException::class.java) { repo.insert(collision) }
        assertEquals("user.onboard", thrown.name)
        assertEquals(1, thrown.version)
    }

    @Test
    fun `findByNameAndVersion returns the matching row`() {
        repo.registerNextVersion("user.onboard", """{"v":1}""")
        val v2 = repo.registerNextVersion("user.onboard", """{"v":2}""")

        val found = repo.findByNameAndVersion("user.onboard", 2)
        assertNotNull(found)
        assertEquals(v2.id, found!!.id)
        assertJsonEquals("""{"v":2}""", found.definition)
    }

    @Test
    fun `count reflects total registered rows`() {
        assertEquals(0L, repo.count())
        repo.registerNextVersion("user.onboard", "{}")
        repo.registerNextVersion("user.onboard", "{}")
        repo.registerNextVersion("payment.capture", "{}")
        assertEquals(3L, repo.count())
    }

    @Test
    fun `registerNextVersion survives concurrent writers`() {
        val threads = 8
        val perThread = 10
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val futures =
                (1..threads).map {
                    pool.submit {
                        repeat(perThread) {
                            repo.registerNextVersion("user.onboard", """{"burst":true}""")
                        }
                    }
                }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        val versions =
            jdbcTemplate.queryForList(
                "SELECT version FROM pgpilot.workflows WHERE name = ? ORDER BY version",
                Int::class.java,
                "user.onboard",
            )

        val expected = (1..(threads * perThread)).toList()
        assertEquals(expected, versions, "expected contiguous versions 1..${threads * perThread}")
        assertTrue(versions.distinct().size == versions.size, "versions must be unique")
    }
}
