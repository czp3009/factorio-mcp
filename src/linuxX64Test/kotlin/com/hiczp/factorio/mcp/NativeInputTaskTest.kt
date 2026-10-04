@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.posix.getpid

class NativeInputTaskTest {
    @Test
    fun retainsIndependentMappedProgressAndPublishesCooperativeCancellation() = runBlocking {
        val request =
            InputSequenceRequest(
                listOf(InputEntry(InputControl.Keyboard("W"), listOf(InputInterval(0, 2)))),
                true,
            )
        val task = NativeInputTask(getpid(), request, { listOf(42u) }, { true })
        SharedMapping.open(
                "/proc/${getpid()}/fd/${task.descriptorNumber}",
                sizeOf<FmLinuxInputTask>(),
            )
            .use { resident ->
                val wire = resident.memory.reinterpret<FmLinuxInputTask>().pointed
                assertEquals(getpid().toUInt(), wire.ownerPid)
                assertEquals(getpid().toUInt(), wire.targetPid)
                assertEquals(1u, wire.stopPrevious)
                assertEquals(42u, wire.entries[0].code)
                fm_ipc_store(fm_linux_input_state_word(wire.ptr), 1u)
                val closing = async(start = CoroutineStart.UNDISPATCHED) { task.close() }
                assertEquals(1u, wire.cancel)
                assertFalse(closing.isCompleted)
                wire.completedEntries = 1u
                wire.evaluatedTicks = 3uL
                fm_ipc_store(fm_linux_input_state_word(wire.ptr), 3u)
                val result = closing.await()
                assertFalse(result.completed)
                assertEquals(1, result.completedEntries)
                assertEquals(3L, result.evaluatedTicks)
                assertFailsWith<IllegalStateException> { task.descriptorNumber }
                // The resident owns an independent mapping even after Kotlin releases its local
                // resources.
                assertEquals(3u, fm_linux_input_state(wire.ptr))
                assertEquals(result, task.close())
            }
    }

    @Test
    fun closesRejectedAdmissionWithoutCancelingAnUnownedTask() = runBlocking {
        val task =
            NativeInputTask(
                getpid(),
                InputSequenceRequest(emptyList(), true),
                { emptyList() },
                { true },
            )
        SharedMapping.open(
                "/proc/${getpid()}/fd/${task.descriptorNumber}",
                sizeOf<FmLinuxInputTask>(),
            )
            .use { resident ->
                val wire = resident.memory.reinterpret<FmLinuxInputTask>()
                val result = task.close()
                assertFalse(result.completed)
                assertEquals("Input was not admitted", result.reason)
                assertEquals(0u, wire.pointed.cancel)
                assertTrue(resident.size > 0)
            }
    }
}
