package dev.pgpilot.orchestrator.runs

/**
 * Lifecycle of a workflow execution. The DB-side `runs.status` column is a
 * text CHECK constraint; this enum is the canonical client-side mapping.
 *
 * Transitions:
 *   PENDING ──claim──▶ RUNNING ──complete──▶ COMPLETED
 *                               └─fail──────▶ FAILED
 *   PENDING | RUNNING ──cancel──▶ CANCELLED
 *
 * Terminal states (COMPLETED, FAILED, CANCELLED) must set `completed_at`.
 */
enum class RunStatus(
    val dbValue: String,
) {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    companion object {
        private val BY_DB_VALUE = entries.associateBy { it.dbValue }

        fun fromDbValue(value: String): RunStatus = BY_DB_VALUE[value] ?: error("unknown run status: '$value'")
    }
}
