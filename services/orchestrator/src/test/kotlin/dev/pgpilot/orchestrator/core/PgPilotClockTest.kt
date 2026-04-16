package dev.pgpilot.orchestrator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PgPilotClockTest {
    @Test
    fun `fixed clock returns the same instant for every call`() {
        val pinned = Instant.parse("2026-04-16T12:00:00Z")
        val clock = PgPilotClock.fixed(pinned)

        assertEquals(pinned, clock.now())
        assertEquals(pinned, clock.now())
    }

    @Test
    fun `from delegates to an arbitrary java Clock`() {
        val pinned = Instant.parse("2026-04-16T12:00:00Z")
        val clock = PgPilotClock.from(Clock.fixed(pinned, ZoneOffset.UTC))

        assertEquals(pinned, clock.now())
    }

    @Test
    fun `System clock advances across calls`() {
        val first = PgPilotClock.System.now()
        Thread.sleep(2)
        val second = PgPilotClock.System.now()

        assertNotEquals(first, second)
    }
}
