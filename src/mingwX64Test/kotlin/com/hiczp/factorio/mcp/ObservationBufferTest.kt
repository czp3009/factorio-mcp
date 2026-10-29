@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlin.test.*
import kotlinx.cinterop.*

class ObservationBufferTest {
    @Test
    fun transfersMoreThanSixteenMiBWithoutRepeatingOrSkippingBytes() = memScoped<Unit> {
        val original = ByteArray(17 * 1024 * 1024 + 7) { (it % 251).toByte() }
        original.usePinned { pinned ->
            val buffer = alloc<FmObservationBuffer>()
            buffer.address = pinned.addressOf(0).rawValue.toLong().toULong()
            buffer.size = original.size.toULong()
            var transferred = 0
            var calls = 0
            val copied = buffer.readObservationBytes { address, size ->
                assertEquals(buffer.address.toLong() + transferred, address)
                assertTrue(size in 1..1024 * 1024)
                transferred += size
                calls++
                readFixtureObservation(address, size)
            }
            assertEquals(original.size, transferred)
            assertEquals(18, calls)
            assertContentEquals(original, copied)
            original.fill(0)
            assertEquals(1, copied[1].toInt())
        }
    }

    @Test
    fun recordChunksPreserveOrderAndCopyEveryRecordBeforeStorageChanges() = memScoped<Unit> {
        val buffer = alloc<FmObservationBuffer>()
        val records = observationArray<FmControl>(buffer, 5000)
        repeat(5000) { records[it].usage = it }
        var calls = 0
        val result = buffer.readObservationRecords<FmControl, Int>(5000u, { address, size ->
            calls++
            assertEquals(0, size % sizeOf<FmControl>().toInt())
            readFixtureObservation(address, size)
        }) { it.usage }
        assertTrue(calls > 1)
        repeat(5000) { records[it].usage = -1 }
        assertEquals((0 until 5000).toList(), result)
        buffer.size--
        assertFails { buffer.readObservationRecords<FmControl, Int>(5000u, ::readFixtureObservation) { it.usage } }
    }

    @Test
    fun rejectsMalformedRangesAndIncompleteTransfers() = memScoped<Unit> {
        val buffer = alloc<FmObservationBuffer>()
        buffer.address = Long.MAX_VALUE.toULong()
        buffer.size = 2u
        assertFails { buffer.readObservationBytes { _, _ -> error("Must validate before reading") } }
        buffer.address = 1u
        buffer.size = 2u
        assertFails { buffer.readObservationBytes { _, _ -> byteArrayOf(1) } }
        buffer.address = 0u
        buffer.size = 0u
        assertContentEquals(byteArrayOf(), buffer.readObservationBytes { _, _ -> error("Empty buffer must not read") })
    }
}
