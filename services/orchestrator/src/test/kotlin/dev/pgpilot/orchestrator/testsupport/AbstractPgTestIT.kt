package dev.pgpilot.orchestrator.testsupport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Sanity check for [AbstractPgTest]: reaches into the live container, runs a
 * trivial JDBC query, and asserts the result. Acts as the integration-test
 * smoke signal that proves the harness itself works before more substantial
 * suites (schema, repositories, Flyway) are layered on top.
 */
class AbstractPgTestIT : AbstractPgTest() {
    @Test
    fun `testcontainers postgres responds to SELECT 1`() {
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT 1 AS ok").use { rs ->
                    assertEquals(true, rs.next(), "expected one result row")
                    assertEquals(1, rs.getInt("ok"))
                }
            }
        }
    }

    @Test
    fun `reports the expected postgres server version`() {
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SHOW server_version").use { rs ->
                    rs.next()
                    val version = rs.getString(1)
                    // container is pinned to 17.2 via AbstractPgTest
                    assertEquals(true, version.startsWith("17"), "expected PG 17.x, got $version")
                }
            }
        }
    }
}
