package dev.pgpilot.orchestrator.audit

import java.util.UUID

/**
 * Append-only store over `pgpilot.audit_log`.
 *
 * Entries are never updated or deleted by application code — the trail
 * is deliberately immutable. Reads support dashboard / operator
 * workflows.
 */
interface AuditRepository {
    /**
     * Records the given entry. Caller owns id + timestamp.
     */
    fun record(entry: AuditEntry): AuditEntry

    fun findById(id: UUID): AuditEntry?

    /**
     * History for a specific subject, newest first.
     */
    fun listBySubject(
        subjectType: String,
        subjectId: UUID,
        limit: Int,
    ): List<AuditEntry>

    /**
     * Actions originated by a specific actor, newest first.
     */
    fun listByActor(
        actorType: ActorType,
        actorId: String,
        limit: Int,
    ): List<AuditEntry>

    /** Global newest-first feed. */
    fun listRecent(limit: Int): List<AuditEntry>

    fun count(): Long
}
