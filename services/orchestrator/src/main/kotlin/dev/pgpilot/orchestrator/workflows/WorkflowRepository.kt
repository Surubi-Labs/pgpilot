package dev.pgpilot.orchestrator.workflows

import java.util.UUID

/**
 * Read/write access to `pgpilot.workflows`.
 *
 * Implementations must preserve the append-only versioning invariant: the
 * only way to mutate an existing workflow is to append a new row with a
 * higher [Workflow.version]. Rows are never updated after insert.
 */
interface WorkflowRepository {
    /**
     * Inserts a fully-formed workflow. Caller is responsible for picking
     * the next version (most callers should prefer [registerNextVersion]).
     *
     * @throws org.springframework.dao.DuplicateKeyException if `(name, version)` already exists.
     */
    fun insert(workflow: Workflow): Workflow

    /** Fetches a workflow by its primary key, or null if missing. */
    fun findById(id: UUID): Workflow?

    /** Highest-version row for [name], or null if no workflow with that name has ever been registered. */
    fun findLatestByName(name: String): Workflow?

    /** Exact lookup by (name, version), or null if missing. */
    fun findByNameAndVersion(
        name: String,
        version: Int,
    ): Workflow?

    /**
     * Appends a new version of [name] with the given [definition].
     *
     * If the workflow has never been registered, emits version 1. Otherwise
     * emits `latestVersion + 1`. Concurrent callers are safe — the `(name,
     * version)` unique constraint lets the loser retry.
     */
    fun registerNextVersion(
        name: String,
        definition: String,
    ): Workflow

    /** Total row count; cheap diagnostic for tests + admin dashboards. */
    fun count(): Long
}
