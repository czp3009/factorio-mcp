@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmInputTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.runBlocking
import platform.windows.*

class NativeInputTaskTest {
    @Test
    fun motionWirePreservesFullTickRangeAndCoordinates() = runBlocking {
        val request =
            InputSequenceRequest(
                listOf(
                    InputEntry(
                        InputControl.Pointer(
                            InputPath(
                                "world",
                                InputPoint(-1.5, 20.5),
                                InputPoint(50.5, 60.5),
                                true,
                            ),
                            true,
                        ),
                        listOf(InputInterval(2, MAX_INPUT_TICK)),
                    )
                ),
                false,
            )
        val task = NativeInputTask(GetCurrentProcessId(), request, { emptyList() }, { false })
        try {
            val mapping = checkNotNull(OpenFileMappingW(FILE_MAP_READ.toUInt(), 0, task.name))
            try {
                val view =
                    checkNotNull(
                        MapViewOfFile(
                            mapping,
                            FILE_MAP_READ.toUInt(),
                            0u,
                            0u,
                            sizeOf<FmInputTask>().toULong(),
                        )
                    )
                try {
                    val row = view.reinterpret<FmInputTask>().pointed.entries[0]
                    assertEquals(1u, row.intervalCount)
                    assertEquals(2u, row.intervals[0].first)
                    assertEquals(UInt.MAX_VALUE, row.intervals[0].last)
                    assertEquals(-1.5, row.fromX)
                    assertEquals(60.5, row.toY)
                    assertEquals(1u, row.space)
                    assertEquals(1u, row.tileCenters)
                    assertEquals(3u, row.kind)
                } finally {
                    UnmapViewOfFile(view)
                }
            } finally {
                CloseHandle(mapping)
            }
        } finally {
            task.close()
        }
    }

    @Test
    fun typedIntervalsCannotOverrunTheWireOrBypassOrdering() {
        for (intervals in
            listOf(
                List(65) { InputInterval(it.toLong(), it.toLong()) },
                listOf(InputInterval(2, 1)),
                listOf(InputInterval(0, 5), InputInterval(6, MAX_INPUT_TICK + 1)),
            )) {
            assertFailsWith<IllegalArgumentException> {
                NativeInputTask(
                    GetCurrentProcessId(),
                    InputSequenceRequest(
                        listOf(InputEntry(InputControl.Keyboard("W"), intervals)),
                        false,
                    ),
                    { listOf(26u) },
                    { false },
                )
            }
        }
    }

    @Test
    fun cleanupRetainsEachResourceUntilItsReleaseSucceeds() = runBlocking {
        var failUnmap = true
        var failClose = true
        var unmaps = 0
        var closes = 0
        val task =
            NativeInputTask(
                GetCurrentProcessId(),
                InputSequenceRequest(emptyList(), false),
                { emptyList() },
                { true },
                unmap = {
                    unmaps++
                    if (failUnmap) false else UnmapViewOfFile(it) != 0
                },
                closeMapping = {
                    closes++
                    if (failClose) false else CloseHandle(it) != 0
                },
            )
        try {
            assertFailsWith<IllegalStateException> { task.close() }
            assertEquals(0, closes)
            failUnmap = false
            assertFailsWith<IllegalStateException> { task.close() }
            assertEquals(2, unmaps)
            failClose = false
            val result = task.close()
            assertFalse(result.completed)
            assertEquals(2, unmaps)
            assertEquals(2, closes)
            assertEquals(result, task.close())
            assertEquals(2, closes)
        } finally {
            failUnmap = false
            failClose = false
            task.close()
        }
    }
}
