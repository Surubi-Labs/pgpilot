package dev.pgpilot.orchestrator.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Abstraction over `now()` that tests can override.
 *
 * Everywhere the orchestrator reads the wall clock should go through this
 * interface. Tests that need deterministic timestamps pass a
 * [FixedPgPilotClock] or a Java [Clock] advanced manually.
 */
fun interface PgPilotClock {
    fun now(): Instant

    companion object {
        /**
         * Default production clock — real UTC wall-clock time.
         */
        val System: PgPilotClock = PgPilotClock { Instant.now(Clock.systemUTC()) }

        /**
         * Builds a PgPilotClock that delegates to a standard [Clock].
         * Handy in tests where you already have a fixed/offset Clock.
         */
        fun from(clock: Clock): PgPilotClock = PgPilotClock { clock.instant() }

        /**
         * Builds a clock pinned to a specific [Instant].
         */
        fun fixed(instant: Instant): PgPilotClock = PgPilotClock.from(Clock.fixed(instant, ZoneOffset.UTC))
    }
}
