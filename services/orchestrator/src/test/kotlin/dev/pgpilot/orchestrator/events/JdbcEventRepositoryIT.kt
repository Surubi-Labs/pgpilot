package dev.pgpilot.orchestrator.events

import com.fasterxml.jackson.databind.ObjectMapper
import dev.pgpilot.orchestrator.core.UuidV7
import dev.pgpilot.orchestrator.testsupport.AbstractSchemaTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JdbcEventRepositoryIT : AbstractSchemaTest() {
    private val repo: EventRepository by lazy { JdbcEventRepository(jdbcTemplate) }
    private val json = ObjectMapper()

    private fun assertJsonEquals(
        expected: String,
        actual: String?,
    ) {
        assertNotNull(actual)
        assertEquals(json.readTree(expected), json.readTree(actual!!))
    }

    private fun buildEvent(
        type: String = "stripe.checkout.completed",
        source: String = Event.webhookSource("stripe"),
        externalId: String? = "evt_${UuidV7.generate()}",
        payload: String = """{"data":{"id":"cs_123"}}""",
        receivedAt: Instant = Instant.parse("2026-04-16T12:00:00Z"),
    ): Event =
        Event(
            id = UuidV7.generate(),
            type = type,
            source = source,
            externalId = externalId,
            payload = payload,
            receivedAt = receivedAt,
        )

    @Test
    fun `insert round-trips all fields`() {
        val event = buildEvent()
        repo.insert(event)

        val loaded = repo.findById(event.id)!!
        assertEquals(event.id, loaded.id)
        assertEquals(event.type, loaded.type)
        assertEquals(event.source, loaded.source)
        assertEquals(event.externalId, loaded.externalId)
        assertJsonEquals(event.payload, loaded.payload)
        assertEquals(event.receivedAt, loaded.receivedAt)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(repo.findById(UuidV7.generate()))
    }

    @Test
    fun `insertIdempotent dedupes on (source, external_id)`() {
        val first = buildEvent(externalId = "evt_abc")
        val dupe =
            first.copy(
                id = UuidV7.generate(),
                payload = """{"data":{"different":true}}""",
            )

        repo.insertIdempotent(first)
        val result = repo.insertIdempotent(dupe)

        assertEquals(first.id, result.id, "dedup should return the original event")
        assertEquals(1L, repo.count())
        assertJsonEquals(first.payload, repo.findById(first.id)!!.payload)
    }

    @Test
    fun `insertIdempotent always inserts when externalId is null`() {
        repo.insertIdempotent(buildEvent(externalId = null))
        repo.insertIdempotent(buildEvent(externalId = null))
        assertEquals(2L, repo.count())
    }

    @Test
    fun `insertIdempotent is safe under concurrent duplicate deliveries`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val winners = AtomicInteger()

        try {
            val futures =
                (1..threads).map {
                    pool.submit {
                        val e = buildEvent(externalId = "evt_shared")
                        val result = repo.insertIdempotent(e)
                        if (result.id == e.id) winners.incrementAndGet()
                    }
                }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        assertEquals(1L, repo.count())
        assertEquals(1, winners.get())
    }

    @Test
    fun `insert throws DuplicateKeyException on (source, external_id) collision`() {
        val first = buildEvent(externalId = "evt_dup")
        val dupe = first.copy(id = UuidV7.generate())
        repo.insert(first)
        assertThrows(DuplicateKeyException::class.java) { repo.insert(dupe) }
    }

    @Test
    fun `same externalId in different sources coexists`() {
        repo.insert(buildEvent(source = Event.webhookSource("stripe"), externalId = "evt_same"))
        repo.insert(buildEvent(source = Event.webhookSource("github"), externalId = "evt_same"))
        assertEquals(2L, repo.count())
    }

    @Test
    fun `listByType returns events newest first`() {
        val base = Instant.parse("2026-04-16T12:00:00Z")
        val a = buildEvent(type = "stripe.checkout.completed", externalId = "a", receivedAt = base)
        val b = buildEvent(type = "stripe.checkout.completed", externalId = "b", receivedAt = base.plusSeconds(60))
        val c = buildEvent(type = "stripe.checkout.completed", externalId = "c", receivedAt = base.plusSeconds(30))
        val noise = buildEvent(type = "other.type", externalId = "x", receivedAt = base.plusSeconds(120))
        listOf(a, b, c, noise).forEach { repo.insert(it) }

        val list = repo.listByType("stripe.checkout.completed", limit = 10)
        assertEquals(listOf(b.id, c.id, a.id), list.map { it.id })
    }

    @Test
    fun `findBySourceAndExternalId returns null when not found`() {
        assertNull(repo.findBySourceAndExternalId("webhook:stripe", "missing"))
    }

    @Test
    fun `CHECK constraints reject empty type and source`() {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO events (id, type, source, payload, received_at)
                VALUES (?, '', 'webhook:stripe', '{}'::jsonb, now())
                """.trimIndent(),
                UuidV7.generate(),
            )
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO events (id, type, source, payload, received_at)
                VALUES (?, 'x.y', '', '{}'::jsonb, now())
                """.trimIndent(),
                UuidV7.generate(),
            )
        }
    }
}
