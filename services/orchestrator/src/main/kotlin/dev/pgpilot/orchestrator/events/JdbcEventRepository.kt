package dev.pgpilot.orchestrator.events

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class JdbcEventRepository(
    private val jdbcTemplate: JdbcTemplate,
) : EventRepository {
    override fun insert(event: Event): Event {
        jdbcTemplate.update(
            """
            INSERT INTO events (id, type, source, external_id, payload, received_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            event.id,
            event.type,
            event.source,
            event.externalId,
            event.payload,
            Timestamp.from(event.receivedAt),
        )
        return event
    }

    override fun insertIdempotent(event: Event): Event {
        if (event.externalId == null) return insert(event)

        findBySourceAndExternalId(event.source, event.externalId)?.let { return it }
        return try {
            insert(event)
        } catch (_: DuplicateKeyException) {
            findBySourceAndExternalId(event.source, event.externalId)
                ?: error(
                    "lost idempotency race and row still missing (source=${event.source}, " +
                        "externalId=${event.externalId})",
                )
        }
    }

    override fun findById(id: UUID): Event? =
        jdbcTemplate
            .query("$SELECT_ALL WHERE id = ?", MAPPER, id)
            .singleOrNull()

    override fun findBySourceAndExternalId(
        source: String,
        externalId: String,
    ): Event? =
        jdbcTemplate
            .query("$SELECT_ALL WHERE source = ? AND external_id = ?", MAPPER, source, externalId)
            .singleOrNull()

    override fun listByType(
        type: String,
        limit: Int,
    ): List<Event> =
        jdbcTemplate.query(
            "$SELECT_ALL WHERE type = ? ORDER BY received_at DESC LIMIT ?",
            MAPPER,
            type,
            limit,
        )

    override fun count(): Long {
        val n = jdbcTemplate.queryForObject("SELECT count(*) FROM events", Long::class.java)
        return n ?: 0L
    }

    private companion object {
        const val SELECT_ALL: String =
            """
            SELECT id, type, source, external_id, payload, received_at
              FROM events
            """

        val MAPPER: RowMapper<Event> =
            RowMapper { rs, _ ->
                Event(
                    id = rs.getObject("id", UUID::class.java),
                    type = rs.getString("type"),
                    source = rs.getString("source"),
                    externalId = rs.getString("external_id"),
                    payload = rs.getString("payload") ?: "null",
                    receivedAt = rs.getTimestamp("received_at").toInstant(),
                )
            }
    }
}
