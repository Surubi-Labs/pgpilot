package dev.pgpilot.orchestrator.audit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.pgpilot.orchestrator.core.UuidV7
import dev.pgpilot.orchestrator.testsupport.AbstractSchemaTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID

class JdbcAuditRepositoryIT : AbstractSchemaTest() {
    private val repo: AuditRepository by lazy { JdbcAuditRepository(jdbcTemplate) }
    private val json = ObjectMapper()

    private fun assertJsonEquals(
        expected: String,
        actual: String?,
    ) {
        assertNotNull(actual)
        assertEquals(json.readTree(expected), json.readTree(actual!!))
    }

    @Suppress("LongParameterList") // legitimate optional-args test builder
    private fun entry(
        actorType: ActorType = ActorType.USER,
        actorId: String? = "usr_123",
        action: String = "run.triggered",
        subjectType: String = AuditSubjects.RUN,
        subjectId: UUID? = UuidV7.generate(),
        before: String? = null,
        after: String? = """{"status":"pending"}""",
        metadata: String = """{"trace_id":"t-1"}""",
        ip: String? = "203.0.113.1",
        userAgent: String? = "pgpilot-sdk/0.1",
        at: Instant = Instant.parse("2026-04-16T12:00:00Z"),
    ): AuditEntry =
        AuditEntry(
            id = UuidV7.generate(),
            actorType = actorType,
            actorId = actorId,
            actorName = "Juano",
            action = action,
            subjectType = subjectType,
            subjectId = subjectId,
            before = before,
            after = after,
            metadata = metadata,
            ip = ip,
            userAgent = userAgent,
            at = at,
        )

    @Test
    fun `record round-trips every column`() {
        val e = entry()
        repo.record(e)

        val loaded = repo.findById(e.id)!!
        assertEquals(e.actorType, loaded.actorType)
        assertEquals(e.actorId, loaded.actorId)
        assertEquals(e.action, loaded.action)
        assertEquals(e.subjectType, loaded.subjectType)
        assertEquals(e.subjectId, loaded.subjectId)
        assertNull(loaded.before)
        assertJsonEquals(e.after!!, loaded.after)
        assertJsonEquals(e.metadata, loaded.metadata)
        assertEquals(e.ip, loaded.ip)
        assertEquals(e.userAgent, loaded.userAgent)
        assertEquals(e.at, loaded.at)
    }

    @Test
    fun `listBySubject returns newest-first for a subject`() {
        val subjectId = UuidV7.generate()
        val base = Instant.parse("2026-04-16T12:00:00Z")
        val a = entry(subjectId = subjectId, action = "run.triggered", at = base)
        val b = entry(subjectId = subjectId, action = "run.started", at = base.plusSeconds(60))
        val c = entry(subjectId = subjectId, action = "run.completed", at = base.plusSeconds(30))
        val noise = entry(subjectId = UuidV7.generate(), at = base.plusSeconds(120))
        listOf(a, b, c, noise).forEach { repo.record(it) }

        val list = repo.listBySubject(AuditSubjects.RUN, subjectId, limit = 10)
        assertEquals(listOf(b.id, c.id, a.id), list.map { it.id })
    }

    @Test
    fun `listByActor returns newest-first for one actor`() {
        val base = Instant.parse("2026-04-16T12:00:00Z")
        val mine1 = entry(actorId = "usr_me", at = base)
        val mine2 = entry(actorId = "usr_me", at = base.plusSeconds(60))
        val other = entry(actorId = "usr_other", at = base.plusSeconds(30))
        listOf(mine1, mine2, other).forEach { repo.record(it) }

        val list = repo.listByActor(ActorType.USER, "usr_me", limit = 10)
        assertEquals(listOf(mine2.id, mine1.id), list.map { it.id })
    }

    @Test
    fun `listRecent returns all entries newest-first`() {
        val base = Instant.parse("2026-04-16T12:00:00Z")
        val older = entry(at = base)
        val newer = entry(at = base.plusSeconds(60))
        repo.record(older)
        repo.record(newer)

        val list = repo.listRecent(limit = 10)
        assertEquals(listOf(newer.id, older.id), list.map { it.id })
    }

    @Test
    fun `record accepts null actor_id, subject_id, and IP for system actions`() {
        val e =
            entry(
                actorType = ActorType.SYSTEM,
                actorId = null,
                subjectId = null,
                ip = null,
                userAgent = null,
                action = "scheduler.heartbeat",
                subjectType = "system",
                before = null,
                after = null,
            )
        repo.record(e)

        val loaded = repo.findById(e.id)!!
        assertEquals(ActorType.SYSTEM, loaded.actorType)
        assertNull(loaded.actorId)
        assertNull(loaded.subjectId)
        assertNull(loaded.ip)
    }

    @Test
    fun `default metadata is an empty JSON object when not provided`() {
        val id = UuidV7.generate()
        jdbcTemplate.update(
            """
            INSERT INTO audit_log (id, actor_type, action, subject_type, at)
            VALUES (?, 'system', 'bootstrap', 'system', now())
            """.trimIndent(),
            id,
        )
        val loaded = repo.findById(id)!!
        assertJsonEquals("{}", loaded.metadata)
    }

    @Test
    fun `CHECK rejects unknown actor_type via direct JDBC`() {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO audit_log (id, actor_type, action, subject_type, at)
                VALUES (?, 'stranger', 'a', 'run', now())
                """.trimIndent(),
                UuidV7.generate(),
            )
        }
    }

    @Test
    fun `CHECK rejects empty action or subject_type`() {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO audit_log (id, actor_type, action, subject_type, at)
                VALUES (?, 'system', '', 'run', now())
                """.trimIndent(),
                UuidV7.generate(),
            )
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO audit_log (id, actor_type, action, subject_type, at)
                VALUES (?, 'system', 'a', '', now())
                """.trimIndent(),
                UuidV7.generate(),
            )
        }
    }

    @Test
    fun `count reflects total rows`() {
        assertEquals(0L, repo.count())
        repo.record(entry())
        repo.record(entry())
        assertEquals(2L, repo.count())
    }
}
