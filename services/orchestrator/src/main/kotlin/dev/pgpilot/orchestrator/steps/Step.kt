package dev.pgpilot.orchestrator.steps

import java.time.Instant
import java.util.UUID

/**
 * A single durable step within a workflow run.
 *
 * Steps are the memoization unit: once [StepStatus.COMPLETED], the SDK
 * never re-invokes the user's handler on replay — the stored [output] is
 * surfaced instead.
 *
 * The name is unique within a run, which is how the SDK re-associates its
 * deterministic `step.run("name", ...)` calls with their stored results.
 * Fan-out children use suffixed names (e.g. `"process[0]"`) so this
 * invariant still holds; [fanGroupId] + [fanIndex] identify them.
 *
 * @property waitEventType When `status = WAITING`, the event type the step
 *   is blocked on.
 * @property waitMatch JSONB fragment used with `@>` to match incoming
 *   events to this step's subscription.
 * @property expiresAt Deadline for `step.waitForEvent` timeouts; null
 *   otherwise.
 * @property fanParentStepId The `step.fan(...)` row that spawned this
 *   child (null for non-fan steps).
 */
data class Step(
    val id: UUID,
    val runId: UUID,
    val name: String,
    val status: StepStatus,
    val attempt: Int,
    val input: String,
    val output: String?,
    val error: String?,
    val waitEventType: String?,
    val waitMatch: String?,
    val scheduledAt: Instant?,
    val expiresAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val fanGroupId: UUID?,
    val fanIndex: Int?,
    val fanParentStepId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
