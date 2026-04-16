package dev.pgpilot.orchestrator.audit

/**
 * Restricted set of audit-log actor kinds. Mirrors the DB CHECK on
 * `audit_log.actor_type`.
 */
enum class ActorType(
    val dbValue: String,
) {
    /** Dashboard user (Clerk-authenticated). */
    USER("user"),

    /** Public API call authenticated with an API key. */
    API_KEY("api_key"),

    /** Internal scheduled jobs, migrations, and background flows. */
    SYSTEM("system"),

    /** Orchestrator worker processes. */
    WORKER("worker"),
    ;

    companion object {
        private val BY_DB_VALUE = entries.associateBy { it.dbValue }

        fun fromDbValue(value: String): ActorType = BY_DB_VALUE[value] ?: error("unknown actor type: '$value'")
    }
}
