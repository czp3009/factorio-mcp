@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.Shared
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import platform.windows.CloseHandle
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.UnmapViewOfFile

/** Close dependent resources first; retain each failed release for the next cleanup attempt. */
internal class WindowsConnectionResources(
    process: HANDLE,
    private val unmap: (COpaquePointer) -> Boolean = { UnmapViewOfFile(it) != 0 },
    private val closeHandle: (HANDLE) -> Boolean = { CloseHandle(it) != 0 },
) {
    var process: HANDLE? = process
        private set

    var gate: HANDLE? = null
    var mapping: HANDLE? = null
    var shared: CPointer<Shared>? = null

    fun close() {
        shared?.let {
            check(unmap(it)) { "Cannot unmap resident IPC: ${GetLastError()}" }
            shared = null
        }
        mapping?.let {
            check(closeHandle(it)) { "Cannot close resident IPC mapping: ${GetLastError()}" }
            mapping = null
        }
        gate?.let {
            check(closeHandle(it)) { "Cannot close resident mutex: ${GetLastError()}" }
            gate = null
        }
        process?.let {
            check(closeHandle(it)) { "Cannot close target process handle: ${GetLastError()}" }
            process = null
        }
    }
}
