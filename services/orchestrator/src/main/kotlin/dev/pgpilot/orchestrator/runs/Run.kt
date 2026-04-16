package dev.pgpilot.orchestrator.runs

import java.time.Instant
import java.util.UUID

/**
 * A single workflow execution.
 *
 * Runs transition through [RunStatus] states and are claimed by orchestrator
 * workers via `FOR UPDATE SKIP LOCKED`. The [idempotencyKey] guards against
 * duplicate inserts when a customer retries `pgpilot.trigger(...)`.
 *
 * @property input JSON string supplied at trigger time.
 * @property output JSON string produced on successful completion (null otherwise).
 * @property error Structured error payload (JSON) for failed runs.
 * @property parentRunId Direct parent if this run was spawned via `step.invoke`.
 * @property rootRunId Root of the run tree; equals [id] for top-level runs.
 * @property depth 0 for root runs, +1 per nesting level.
 * @property claimedBy Opaque worker id currently owning the run, or null.
 * @property heartbeatAt Last heartbeat from the owning worker; drives stale-claim reclaim.
 * @property attempt Number of times the run has been claimed (monotonic).
 */
data class Run(
    val id: UUID,
    val workflowId: UUID,
    val status: RunStatus,
    val idempotencyKey: String?,
    val input: String,
    val output: String?,
    val error: String?,
    val parentRunId: UUID?,
    val rootRunId: UUID?,
    val depth: Int,
    val scheduledAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val heartbeatAt: Instant?,
    val attempt: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
