@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlin.test.*
import kotlinx.cinterop.*
import platform.posix.memset

class ChatSnapshotTest {
    @Test
    fun validatesCompleteRetainedListsAndPreservesRepeatedMessages() = memScoped<Unit> {
        val snapshot = alloc<FmChatSnapshot>()
        memset(snapshot.ptr, 0, sizeOf<FmChatSnapshot>().toULong())
        snapshot.consoleIdentity = 1u
        snapshot.count = 5000u
        snapshot.totals[0] = 5000u
        val rows = observationArray<FmChatRecord>(snapshot.records, 5000)
        repeat(5000) { index ->
            rows[index].identity = (index + 1).toULong()
            rows[index].tick = 20u
            rows[index].playerIndex = UShort.MAX_VALUE.toUInt()
        }
        val read = snapshot.readChatSnapshot(::readFixtureObservation)
        assertEquals(5000, read.records.size)
        assertEquals(65535, read.records.last().playerIndexRaw)
        assertEquals("", read.records.last().text)
        snapshot.totals[0] = 5001u
        assertFails { snapshot.readChatSnapshot(::readFixtureObservation) }
        snapshot.totals[0] = 5000u
        rows[4999].identity = 1u
        assertFails { snapshot.readChatSnapshot(::readFixtureObservation) }
    }

    @Test
    fun rejectsOversizedNativeTextBeforeReadingIt() = memScoped<Unit> {
        val text = alloc<FmObservationBuffer>()
        text.address = 1u
        text.size = FM_OBSERVATION_TEXT_BYTES.toULong() + 1u
        assertFails { text.readObservationText { _, _ -> error("Must validate before reading") } }
    }
}
