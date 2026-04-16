package dev.pgpilot.orchestrator.audit

import java.time.Instant
import java.util.UUID

/**
 * A single append-only entry in `pgpilot.audit_log`.
 *
 * The shape is polymorphic: every entity that wants an audit trail
 * identifies itself with [subjectType] + [subjectId]. That keeps the
 * audit surface closed while letting the orchestrator add entity types
 * later without schema churn.
 *
 * @property action Verbed string, lower-case dotted (e.g. `run.triggered`).
 * @property before Pre-change snapshot if the action mutated state.
 * @property after Post-change snapshot if the action mutated state.
 * @property metadata Free-form per-action context (trace ids, reasons).
 */
data class AuditEntry(
    val id: UUID,
    val actorType: ActorType,
    val actorId: String?,
    val actorName: String?,
    val action: String,
    val subjectType: String,
    val subjectId: UUID?,
    val before: String?,
    val after: String?,
    val metadata: String,
    val ip: String?,
    val userAgent: String?,
    val at: Instant,
)
