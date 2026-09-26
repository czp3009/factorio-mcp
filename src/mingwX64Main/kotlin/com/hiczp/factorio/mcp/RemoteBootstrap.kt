@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import platform.windows.*

/** Owns an admitted remote call until Windows confirms its thread has stopped. */
internal class RemoteBootstrap(
    private val process: HANDLE,
    thread: HANDLE,
    argument: COpaquePointer?,
    private val wait: (HANDLE) -> UInt = { WaitForSingleObject(it, 0u) },
) {
    private var thread: HANDLE? = thread
    private var argument = argument

    private fun finished(handle: HANDLE): Boolean =
        when (wait(handle)) {
            WAIT_OBJECT_0 -> true
            WAIT_TIMEOUT.toUInt() -> false
            else -> error("Cannot wait for resident bootstrap: ${GetLastError()}")
        }

    suspend fun await(): UInt {
        val handle = checkNotNull(thread) { "Bootstrap is closed" }
        while (!finished(handle)) delay(10)
        return memScoped {
            val result = alloc<UIntVar>()
            check(GetExitCodeThread(handle, result.ptr) != 0) {
                "Cannot read resident bootstrap result: ${GetLastError()}"
            }
            result.value
        }
    }

    /**
     * A wait failure retains both resources, so cleanup can be retried without replaying the call.
     */
    fun close() {
        val handle = thread ?: return
        val alive = processIsAlive(process)
        check(!alive || finished(handle)) { "Resident bootstrap is still executing" }
        argument?.let {
            check(!alive || VirtualFreeEx(process, it, 0u, MEM_RELEASE.toUInt()) != 0) {
                "Cannot release resident bootstrap argument: ${GetLastError()}"
            }
            argument = null
        }
        check(CloseHandle(handle) != 0) {
            "Cannot close resident bootstrap thread: ${GetLastError()}"
        }
        thread = null
    }
}
