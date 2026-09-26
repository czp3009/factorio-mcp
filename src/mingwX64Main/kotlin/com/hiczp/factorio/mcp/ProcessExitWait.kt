@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import platform.windows.*

/** A failed OS wait is not evidence of process exit. */
internal fun processIsAlive(process: HANDLE?): Boolean =
    when (WaitForSingleObject(process, 0u)) {
        WAIT_TIMEOUT.toUInt() -> true
        WAIT_OBJECT_0 -> false
        else -> error("Cannot check target process state: ${GetLastError()}")
    }

/** Windows owns the wait; its callback only completes a signal, never runs attachment cleanup. */
internal class ProcessExitWait(process: HANDLE) {
    private val signal = CompletableDeferred<Unit>()
    private val reference = StableRef.create(signal)
    private var registration: HANDLE? = null

    init {
        try {
            memScoped {
                val wait = alloc<HANDLEVar>()
                check(
                    RegisterWaitForSingleObject(
                        wait.ptr,
                        process,
                        staticCFunction { context: COpaquePointer?, timedOut: UByte ->
                            if (timedOut == 0.toUByte()) {
                                context!!
                                    .asStableRef<CompletableDeferred<Unit>>()
                                    .get()
                                    .complete(Unit)
                            }
                            Unit
                        },
                        reference.asCPointer(),
                        INFINITE,
                        WT_EXECUTEONLYONCE.toUInt(),
                    ) != 0
                ) {
                    "Cannot register process exit notification: ${GetLastError()}"
                }
                registration = wait.value
            }
        } catch (failure: Throwable) {
            reference.dispose()
            throw failure
        }
    }

    suspend fun await() {
        signal.await()
    }

    /** Unregister before closing the process handle or releasing the callback context. */
    fun close() {
        val wait = registration ?: return
        check(UnregisterWaitEx(wait, INVALID_HANDLE_VALUE) != 0) {
            "Cannot unregister process exit notification: ${GetLastError()}"
        }
        registration = null
        reference.dispose()
        signal.cancel()
    }
}
