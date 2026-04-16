package dev.pgpilot.orchestrator.core

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator
import java.util.UUID

/**
 * Application-side UUID v7 generator.
 *
 * UUID v7 embeds the Unix epoch timestamp in the high bits, so IDs are
 * time-sortable. That keeps BTREE index locality high (new IDs cluster at
 * the right edge of the tree) without leaking insertion rate via
 * bigserial.
 *
 * Generation happens in the orchestrator rather than Postgres so that
 * PgPilot works against managed Postgres instances (14–17) where the
 * `gen_uuidv7()` extension may not be installable.
 */
object UuidV7 {
    private val generator: TimeBasedEpochGenerator = Generators.timeBasedEpochGenerator()

    /**
     * Returns a freshly generated, time-ordered UUID v7.
     */
    fun generate(): UUID = generator.generate()
}
