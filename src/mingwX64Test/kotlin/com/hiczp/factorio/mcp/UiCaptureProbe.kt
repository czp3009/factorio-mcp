@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.EventLayout
import com.hiczp.factorio.mcp.nativebridge.FmSymbol
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.test.assertEquals

/** Test-only inspection after a completed gesture, while the fixture keeps its GUI root stable. */
internal fun assertUiCaptureReleased(pid: UInt) {
    val process =
        checkNotNull(OpenProcess((PROCESS_QUERY_INFORMATION or PROCESS_VM_READ).toUInt(), 0, pid))
    try {
        val symbols = resolveSymbols(process, checkNotNull(processModule(pid)))
        memScoped {
            fun pointer(address: ULong): ULong {
                val value = alloc<ULongVar>()
                val read = alloc<ULongVar>()
                check(
                    ReadProcessMemory(
                        process,
                        address.toLong().toCPointer<ByteVar>(),
                        value.ptr,
                        8uL,
                        read.ptr,
                    ) != 0 && read.value == 8uL
                )
                return value.value
            }

            val events = alloc<EventLayout>()
            symbols.events.write(events)
            val instance = symbols.addresses[FmSymbol.GuiInstance.value.toInt()]
            val gui = pointer(instance)
            check(gui != 0uL)
            val root = pointer(gui + symbols.rootOffset)
            assertEquals(
                0uL,
                pointer(gui + events.guiCaptureTarget),
                "Completed UI gesture retained native mouse capture",
            )
            assertEquals(gui, pointer(instance))
            assertEquals(root, pointer(gui + symbols.rootOffset))
        }
    } finally {
        CloseHandle(process)
    }
}
