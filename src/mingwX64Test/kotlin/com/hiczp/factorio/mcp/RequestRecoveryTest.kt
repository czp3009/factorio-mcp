@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.Shared
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.memset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RequestRecoveryTest {
    @Test
    fun reconnectWaitsWithoutReplacingOrCancellingAdmittedWork() = runBlocking {
        val state = nativeHeap.alloc<Shared>()
        try {
            memset(state.ptr, 0, sizeOf<Shared>().toULong())
            state.command = 2
            state.operation = 5u
            val wait =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitResidentIdle(state.ptr, false) { true }
                }
            assertFalse(wait.isCompleted)
            assertEquals(5u, state.operation)
            assertEquals(0, state.cancel)
            state.command = 3
            withTimeout(1000) { wait.await() }
            assertEquals(0, state.command)
        } finally {
            nativeHeap.free(state)
        }
    }

    @Test
    fun detachCancelsButCallerCancellationDoesNotCancelAnOrphan() = runBlocking {
        val state = nativeHeap.alloc<Shared>()
        try {
            memset(state.ptr, 0, sizeOf<Shared>().toULong())
            state.command = 2
            val reconnect =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitResidentIdle(state.ptr, false) { true }
                }
            reconnect.cancelAndJoin()
            assertEquals(0, state.cancel)
            val detach =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitResidentIdle(state.ptr, true) { true }
                }
            assertEquals(1, state.cancel)
            state.command = 3
            withTimeout(1000) { detach.await() }
            assertEquals(0, state.command)
            state.command = 2
            assertFailsWith<IllegalStateException> { awaitResidentIdle(state.ptr, false) { false } }
            Unit
        } finally {
            nativeHeap.free(state)
        }
    }
}
