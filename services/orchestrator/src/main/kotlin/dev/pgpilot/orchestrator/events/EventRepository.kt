package dev.pgpilot.orchestrator.events

import java.util.UUID

/**
 * Append-only store over `pgpilot.events`.
 *
 * Updates are not supported; events are ingested once and matched against
 * waiting steps (EPIC 4). [insertIdempotent] handles the normal case
 * where a webhook provider retries the same upstream event id.
 */
interface EventRepository {
    /**
     * Inserts the event. Throws [org.springframework.dao.DuplicateKeyException]
     * on a (source, external_id) collision if the upstream has retried;
     * prefer [insertIdempotent] at webhook ingress for automatic dedup.
     */
    fun insert(event: Event): Event

    /**
     * Inserts [event] unless the same (source, external_id) pair already
     * exists, in which case the existing row is returned. Internal events
     * (with `externalId == null`) always insert.
     */
    fun insertIdempotent(event: Event): Event

    fun findById(id: UUID): Event?

    fun findBySourceAndExternalId(
        source: String,
        externalId: String,
    ): Event?

    /**
     * Most-recent-first listing of a single event type. Intended for the
     * dashboard feed; hot-path matching lives in the event matcher.
     */
    fun listByType(
        type: String,
        limit: Int,
    ): List<Event>

    fun count(): Long
}
