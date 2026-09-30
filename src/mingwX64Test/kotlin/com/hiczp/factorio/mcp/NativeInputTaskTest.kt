@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmInputTask
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.runBlocking
import platform.windows.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NativeInputTaskTest {
    @Test
    fun motionWirePreservesFullTickRangeAndCoordinates() = runBlocking {
        val request =
            InputSequenceRequest(
                listOf(
                    InputOperation(
                        listOf(
                            InputControl.Mouse(
                                "left",
                                InputPosition(10, 20),
                                motion =
                                    listOf(
                                        InputMotion(2, InputPosition(30, 40)),
                                        InputMotion(4294967295L, InputPosition(50, 60)),
                                    ),
                            )
                        ),
                        4294967295L,
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
                    val row = view.reinterpret<FmInputTask>().pointed.operations[0]
                    assertEquals(2u, row.motionCount)
                    assertEquals(2u, row.motion[0].tick)
                    assertEquals(UInt.MAX_VALUE, row.motion[1].tick)
                    assertEquals(30, row.motion[0].x)
                    assertEquals(60, row.motion[1].y)
                    assertEquals(1u, row.count)
                    assertEquals(1u, row.buttons[0].device)
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
    fun typedMotionCannotOverrunTheWireOrBypassOrdering() {
        for (motion in
        listOf(
            List(65) { InputMotion(it + 2L, InputPosition(0, 0)) },
            listOf(InputMotion(1, InputPosition(0, 0))),
            listOf(InputMotion(2, InputPosition(-1, 0))),
        )) {
            assertFailsWith<IllegalArgumentException> {
                NativeInputTask(
                    GetCurrentProcessId(),
                    InputSequenceRequest(
                        listOf(
                            InputOperation(
                                listOf(InputControl.Mouse(null, null, motion = motion)),
                                100,
                            )
                        ),
                        false,
                    ),
                    { emptyList() },
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
