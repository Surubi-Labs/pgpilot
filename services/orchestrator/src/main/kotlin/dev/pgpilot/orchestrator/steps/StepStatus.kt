package dev.pgpilot.orchestrator.steps

/**
 * Lifecycle of a single workflow step.
 *
 * Transitions:
 *   PENDING ──claim──▶ RUNNING ──complete──▶ COMPLETED
 *                                └──fail─────▶ FAILED ──retries-exhausted──▶ DEAD_LETTERED
 *                                └──sleep────▶ SLEEPING ──timer-fires──▶ PENDING
 *                                └──waitForEvent──▶ WAITING ──event-match──▶ PENDING
 *                                                  └──timeout────▶ FAILED
 *
 * PENDING → SLEEPING and PENDING → WAITING are also valid direct transitions
 * when the orchestrator materializes a step immediately with a scheduled
 * resume (step.sleep) or event subscription (step.waitForEvent).
 */
enum class StepStatus(
    val dbValue: String,
) {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    WAITING("waiting"),
    SLEEPING("sleeping"),
    DEAD_LETTERED("dead_lettered"),
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == DEAD_LETTERED

    /** True while the step is occupying a worker or scheduler slot. */
    val isActive: Boolean
        get() = this == RUNNING || this == WAITING || this == SLEEPING

    companion object {
        private val BY_DB_VALUE = entries.associateBy { it.dbValue }

        fun fromDbValue(value: String): StepStatus = BY_DB_VALUE[value] ?: error("unknown step status: '$value'")
    }
}
