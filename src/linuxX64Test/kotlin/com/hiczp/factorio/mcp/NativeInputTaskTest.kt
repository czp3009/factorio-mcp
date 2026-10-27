@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxInputTask
import com.hiczp.factorio.mcp.linuxbridge.fm_ipc_store
import com.hiczp.factorio.mcp.linuxbridge.fm_linux_input_state
import com.hiczp.factorio.mcp.linuxbridge.fm_linux_input_state_word
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.*

class NativeInputTaskTest {
    private fun storage() = SharedMapping.create(sizeOf<FmLinuxInputTask>()).also {
        val wire = it.memory.reinterpret<FmLinuxInputTask>().pointed
        wire.ownerPid = getpid().toUInt()
        wire.targetPid = getpid().toUInt()
    }

    @Test
    fun retainsIndependentMappedProgressAndPublishesCooperativeCancellation() = runBlocking {
        val request =
            InputSequenceRequest(
                listOf(InputEntry(InputControl.Keyboard("W"), listOf(InputInterval(0, 2)))),
                true,
            )
        val storage = storage()
        val descriptor = storage.descriptorNumber
        val task = NativeInputTask(getpid(), request, { listOf(42u) }, { true }, storage)
        SharedMapping.open(
            "/proc/${getpid()}/fd/$descriptor",
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
                assertFailsWith<IllegalStateException> { storage.descriptorNumber }
                // The resident owns an independent mapping even after Kotlin releases its local
                // resources.
                assertEquals(3u, fm_linux_input_state(wire.ptr))
                assertEquals(result, task.close())
            }
    }

    @Test
    fun closesRejectedAdmissionWithoutCancelingAnUnownedTask() = runBlocking {
        val storage = storage()
        val descriptor = storage.descriptorNumber
        val task =
            NativeInputTask(
                getpid(),
                InputSequenceRequest(emptyList(), true),
                { emptyList() },
                { true },
                storage,
            )
        SharedMapping.open(
            "/proc/${getpid()}/fd/$descriptor",
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

    @Test
    fun rejectsForeignOrUsedPreparedStorageAndClosesItsLocalHandle() {
        repeat(3) { invalid ->
            val storage = storage()
            val wire = storage.memory.reinterpret<FmLinuxInputTask>().pointed
            when (invalid) {
                0 -> wire.ownerPid += 1u
                1 -> wire.targetPid += 1u
                else -> wire.state = 1u
            }
            assertFailsWith<IllegalArgumentException> {
                NativeInputTask(getpid(), InputSequenceRequest(emptyList(), false), { emptyList() }, { true }, storage)
            }
            assertFailsWith<IllegalStateException> { storage.descriptorNumber }
        }
    }
}
