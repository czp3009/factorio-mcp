@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.ECANCELED
import platform.posix.getpid
import kotlin.test.*

class ResidentChannelTest {
    private fun testChannel(test: suspend CoroutineScope.(ResidentChannel, CPointer<FmLinuxShared>) -> Unit) =
        runBlocking {
            ProcessHandle(getpid()).use { process ->
                SharedMapping.create(sizeOf<FmLinuxShared>()).use { mapping ->
                    val shared = mapping.memory.reinterpret<FmLinuxShared>()
                    fm_ipc_store(fm_linux_attached_word(shared), 1u)
                    withTimeout(5000) { test(ResidentChannel(process, mapping), shared) }
                }
            }
        }

    @Test
    fun retainsFailedActionOwnershipAcrossCompletionAndRefusesNewPayload() = testChannel { channel, shared ->
        shared.pointed.result = -17
        shared.pointed.resultFrame = 11u
        fm_ipc_store(fm_linux_action_word(shared), 1u)
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        assertEquals(ResidentChannel.Result(-17, 11u), channel.reconcile())
        assertTrue(channel.actionOwned)
        var prepared = false
        assertFails {
            channel.execute(FM_LINUX_CLICK, { prepared = true }, { _, result -> result })
        }
        assertFalse(prepared)
        val cleanup = async { channel.execute(FM_LINUX_CLEANUP) }
        yield()
        assertEquals(FM_LINUX_CLEANUP, shared.pointed.operation)
        shared.pointed.result = -17
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        assertEquals(-17, cleanup.await().code)
        assertTrue(channel.actionOwned)
        val detach = async { channel.execute(FM_LINUX_DETACH) }
        yield()
        assertEquals(FM_LINUX_DETACH, shared.pointed.operation)
        fm_ipc_store(fm_linux_action_word(shared), 0u)
        fm_ipc_store(fm_linux_attached_word(shared), 0u)
        shared.pointed.result = 0
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        assertEquals(0, detach.await().code)
        assertFalse(channel.actionOwned)
        assertFalse(channel.attached)
    }

    @Test
    fun refusesNewPayloadUntilAbandonedCompletionIsConsumed() = testChannel { channel, shared ->
        shared.pointed.operation = 99u
        shared.pointed.result = -17
        shared.pointed.resultFrame = 11u
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        assertFails { channel.execute(FM_LINUX_FRAME) }
        assertEquals(99u, shared.pointed.operation)
        assertEquals(ResidentChannel.Result(-17, 11u), channel.reconcile())
        assertEquals(FM_LINUX_IDLE, fm_ipc_load(fm_linux_command_word(shared)))
        assertNull(channel.reconcile())
    }

    @Test
    fun cancelsAnAbandonedPendingCommandWithoutChangingItsPayload() = testChannel { channel, shared ->
        shared.pointed.operation = 77u
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_PENDING)
        val reconciliation = async { channel.reconcile() }
        yield()
        assertFalse(reconciliation.isCompleted)
        assertEquals(1u, fm_ipc_load(fm_linux_cancel_word(shared)))
        assertEquals(77u, shared.pointed.operation)
        shared.pointed.result = -ECANCELED
        shared.pointed.resultFrame = 12u
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        assertEquals(ResidentChannel.Result(-ECANCELED, 12u), reconciliation.await())
    }

    @Test
    fun canceledCallerWaitsForTerminalCleanupAndReceivesItsResult() = testChannel { channel, shared ->
        var terminal: ResidentChannel.Result? = null
        val request = async { terminal = channel.execute(FM_LINUX_FRAME) }
        yield()
        assertEquals(FM_LINUX_PENDING, fm_ipc_load(fm_linux_command_word(shared)))
        request.cancel()
        yield()
        assertFalse(request.isCompleted)
        assertEquals(1u, fm_ipc_load(fm_linux_cancel_word(shared)))
        shared.pointed.result = -ECANCELED
        shared.pointed.resultFrame = 13u
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        request.join()
        assertEquals(ResidentChannel.Result(-ECANCELED, 13u), terminal)
        assertEquals(FM_LINUX_IDLE, fm_ipc_load(fm_linux_command_word(shared)))
    }

    @Test
    fun serializesPayloadPreparationAndCopiesResultsBeforeTheNextWriter() = testChannel { channel, shared ->
        val first = async {
            channel.execute(FM_LINUX_UI, { it.nodeLimit = 17u }, { storage, _ -> storage.snapshot.count })
        }
        yield()
        var preparedSecond = false
        val second = async {
            channel.execute(FM_LINUX_UI, {
                preparedSecond = true
                it.nodeLimit = 23u
                it.snapshot.count = 0u
            }, { storage, _ -> storage.snapshot.count })
        }
        yield()
        assertFalse(preparedSecond)
        assertEquals(17u, shared.pointed.nodeLimit)
        shared.pointed.snapshot.count = 11u
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        assertEquals(11u, first.await())
        while (!preparedSecond) yield()
        assertEquals(23u, shared.pointed.nodeLimit)
        shared.pointed.snapshot.count = 19u
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        assertEquals(19u, second.await())
    }

    @Test
    fun neverRunsPayloadWriterBeforeReconcilingAnAbandonedCommand() = testChannel { channel, shared ->
        shared.pointed.nodeLimit = 73u
        fm_ipc_store(fm_linux_command_word(shared), FM_LINUX_COMPLETE)
        var prepared = false
        assertFails {
            channel.execute(FM_LINUX_UI, {
                prepared = true
                it.nodeLimit = 17u
            }, { _, result -> result })
        }
        assertFalse(prepared)
        assertEquals(73u, shared.pointed.nodeLimit)
        channel.reconcile()
    }
}
