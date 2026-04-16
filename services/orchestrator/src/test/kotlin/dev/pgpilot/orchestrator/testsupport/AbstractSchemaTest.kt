package dev.pgpilot.orchestrator.testsupport

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * Base class for integration tests that exercise the `pgpilot` schema via
 * [JdbcTemplate]. Builds on [AbstractPgTest]:
 *
 * - reuses the shared Testcontainers Postgres across the whole JVM,
 * - runs Flyway migrations once (idempotent re-entries are no-ops),
 * - truncates every table in the schema before each test so suites start
 *   from a deterministic empty state without the churn of recreating
 *   the container.
 *
 * Subclasses interact with the database via [jdbcTemplate]. If a test
 * needs a raw connection or a custom DataSource wrapper, use [dataSource].
 */
@Suppress("UtilityClassWithPublicConstructor")
abstract class AbstractSchemaTest : AbstractPgTest() {
    companion object {
        /**
         * Pool sized for test concurrency without blowing past the
         * container's default max_connections (100).
         */
        @JvmStatic
        protected val dataSource: DataSource by lazy {
            val config =
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 4
                    connectionTimeout = 5_000L
                    poolName = "pgpilot-it"
                }
            HikariDataSource(config).also { ds -> runFlyway(ds) }
        }

        @JvmStatic
        protected val jdbcTemplate: JdbcTemplate by lazy { JdbcTemplate(dataSource) }

        private fun runFlyway(ds: DataSource) {
            Flyway
                .configure()
                .dataSource(ds)
                .schemas(PGPILOT_SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        internal const val PGPILOT_SCHEMA: String = "pgpilot"
    }

    /**
     * Every table currently defined in the schema. Kept in FK-safe deletion
     * order so `TRUNCATE ... CASCADE` still works if a caller disables
     * cascade. New tables added in later migrations must be appended here.
     */
    protected open fun managedTables(): List<String> =
        listOf(
            "audit_log",
            "dead_letters",
            "events",
            "steps",
            "runs",
            "workflows",
        )

    @BeforeEach
    fun truncateSchema() {
        val present = filterExistingTables(managedTables())
        if (present.isEmpty()) return

        val qualified = present.joinToString(", ") { """"$PGPILOT_SCHEMA"."$it"""" }
        jdbcTemplate.execute("TRUNCATE TABLE $qualified RESTART IDENTITY CASCADE")
    }

    private fun filterExistingTables(candidates: List<String>): List<String> =
        if (candidates.isEmpty()) {
            emptyList()
        } else {
            jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_name = ANY (?)
                """.trimIndent(),
                String::class.java,
                PGPILOT_SCHEMA,
                candidates.toTypedArray(),
            )
        }
}
