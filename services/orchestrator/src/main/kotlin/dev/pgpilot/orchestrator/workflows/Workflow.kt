package dev.pgpilot.orchestrator.workflows

import java.time.Instant
import java.util.UUID

/**
 * A registered workflow definition.
 *
 * Workflows are append-only inside the `pgpilot.workflows` table: the SDK
 * re-registering a workflow with a changed [definition] creates a new row
 * with `version = previous + 1`. Active runs always reference a specific
 * `id`, which pins them to the definition they started with.
 *
 * @property id UUID v7 primary key.
 * @property name Stable workflow name supplied by the SDK (e.g. "user.onboard").
 * @property version Monotonic, per-name version (1-based).
 * @property definition Serialized workflow metadata as a JSON string.
 * @property createdAt Insert timestamp in UTC.
 */
data class Workflow(
    val id: UUID,
    val name: String,
    val version: Int,
    val definition: String,
    val createdAt: Instant,
)
