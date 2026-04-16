package dev.pgpilot.orchestrator.testsupport

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Shared Testcontainers Postgres harness for the orchestrator.
 *
 * Spawns a single `postgres:17.2-alpine` container per test JVM and reuses it
 * across every subclass. The connection details are exposed as static-like
 * helpers so any integration test can build a `DataSource`, Flyway runner, or
 * JDBC connection pointing at the live container.
 *
 * Subclasses should annotate themselves with `@Testcontainers` and extend
 * [AbstractPgTest]. We intentionally don't enable `withReuse(true)`: sharing
 * state across JVMs leaks schema pollution between unrelated test runs, and
 * the ~10s container boot is amortized across every suite within a single
 * Gradle invocation.
 *
 * The image can be overridden via `TESTCONTAINERS_POSTGRES_IMAGE` for the
 * CI matrix that exercises PG 14/15/16/17.
 */
@Testcontainers
@Suppress("UtilityClassWithPublicConstructor") // designed as an abstract base for integration suites
abstract class AbstractPgTest {
    companion object {
        private val image: String =
            System
                .getenv("TESTCONTAINERS_POSTGRES_IMAGE")
                ?.takeIf { it.isNotBlank() }
                ?: "postgres:17.2-alpine"

        @JvmStatic
        protected val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("pgpilot_test")
                .withUsername("pgpilot")
                .withPassword("pgpilot")
                .apply { start() }

        @JvmStatic
        protected val jdbcUrl: String get() = postgres.jdbcUrl

        @JvmStatic
        protected val jdbcUser: String get() = postgres.username

        @JvmStatic
        protected val jdbcPassword: String get() = postgres.password
    }
}
