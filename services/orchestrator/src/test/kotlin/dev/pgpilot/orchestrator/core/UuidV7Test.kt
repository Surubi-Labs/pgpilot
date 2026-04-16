package dev.pgpilot.orchestrator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UuidV7Test {
    @Test
    fun `generate returns a version 7 uuid`() {
        val uuid = UuidV7.generate()
        assertEquals(7, uuid.version(), "expected version 7, got $uuid")
        assertEquals(2, uuid.variant(), "expected RFC 4122 variant 10xx")
    }

    @Test
    fun `successive uuids are monotonically sortable as strings`() {
        // UUID v7 encodes millisecond timestamp in the leading hex digits,
        // so lexicographic ordering of the string form matches generation
        // order — which is exactly what BTREE indexes exploit.
        val generated = List(200) { UuidV7.generate().toString() }
        val sorted = generated.sorted()
        assertEquals(generated, sorted, "UUIDv7 sequence should be non-decreasing")
    }

    @Test
    fun `uuids are unique across a burst of generations`() {
        val total = 5_000
        val set = (1..total).asSequence().map { UuidV7.generate() }.toSet()
        assertEquals(total, set.size, "expected $total unique UUIDs, got ${set.size}")
    }

    @Test
    fun `timestamp bits are within the last second`() {
        val before = System.currentTimeMillis()
        val uuid = UuidV7.generate()
        val after = System.currentTimeMillis()

        // UUID v7 layout: top 48 bits = unix_ts_ms (big-endian).
        val tsMillis = uuid.mostSignificantBits ushr 16
        assertTrue(
            tsMillis in (before - 1)..(after + 1),
            "embedded ts=$tsMillis should fall within [$before..$after]",
        )
    }
}
