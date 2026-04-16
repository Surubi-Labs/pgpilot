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
 * [AbstractPgTest]. The container is marked `withReuse(true)` so IDE runs
 * across multiple test files don't tear the container down between suites.
 */
@Testcontainers
@Suppress("UtilityClassWithPublicConstructor") // designed as an abstract base for integration suites
abstract class AbstractPgTest {
    companion object {
        @JvmStatic
        protected val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17.2-alpine"))
                .withDatabaseName("pgpilot_test")
                .withUsername("pgpilot")
                .withPassword("pgpilot")
                .withReuse(true)
                .apply { start() }

        @JvmStatic
        protected val jdbcUrl: String get() = postgres.jdbcUrl

        @JvmStatic
        protected val jdbcUser: String get() = postgres.username

        @JvmStatic
        protected val jdbcPassword: String get() = postgres.password
    }
}
