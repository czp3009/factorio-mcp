@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.EventLayout
import com.hiczp.factorio.mcp.nativebridge.FmSymbol
import com.hiczp.factorio.mcp.nativebridge.SymCleanup
import com.hiczp.factorio.mcp.nativebridge.SymInitializeW
import com.hiczp.factorio.mcp.nativebridge.SymLoadModuleExW
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.cinterop.*
import platform.windows.*

/**
 * Detect inherited GUI modifier state without resetting state owned by the user or another task.
 */
internal fun assertGuiModifiersReleased(pid: UInt) {
    val process =
        checkNotNull(OpenProcess((PROCESS_QUERY_INFORMATION or PROCESS_VM_READ).toUInt(), 0, pid))
    try {
        val module = checkNotNull(processModule(pid))
        val symbols = resolveSymbols(process, module)
        memScoped {
            check(SymInitializeW(process, module.path.substringBeforeLast('\\').wcstr.ptr, 0) != 0)
            try {
                check(
                    SymLoadModuleExW(
                        process,
                        null,
                        module.path.wcstr.ptr,
                        null,
                        module.base,
                        module.size,
                        null,
                        0u,
                    ) != 0uL
                )
                val types = DebugTypes(process, module.base)
                fun pointer(address: ULong): ULong {
                    val value = alloc<ULongVar>()
                    val count = alloc<ULongVar>()
                    check(
                        ReadProcessMemory(
                            process,
                            address.toLong().toCPointer<ByteVar>(),
                            value.ptr,
                            8uL,
                            count.ptr,
                        ) != 0 && count.value == 8uL
                    )
                    return value.value
                }

                val instance = symbols.addresses[FmSymbol.GuiInstance.value.toInt()]
                val gui = pointer(instance)
                check(gui != 0uL)
                val inputOffset = types.pointerMember("agui::Gui", "input", "agui::Input")
                val input = pointer(gui + inputOffset)
                check(input != 0uL)
                for (name in listOf("shift", "alt", "control", "meta")) {
                    val offset = types.byteMember("InputHandlerAgui", name, true)
                    val value = alloc<UByteVar>()
                    val count = alloc<ULongVar>()
                    check(
                        ReadProcessMemory(
                            process,
                            (input + offset).toLong().toCPointer<ByteVar>(),
                            value.ptr,
                            1uL,
                            count.ptr,
                        ) != 0 && count.value == 1uL
                    )
                    assertFalse(
                        value.value != 0.toUByte(),
                        "GUI modifier $name remains pressed; inspect the preceding input or external keyboard state",
                    )
                }
                assertEquals(gui, pointer(instance))
                assertEquals(input, pointer(gui + inputOffset))
            } finally {
                SymCleanup(process)
            }
        }
    } finally {
        CloseHandle(process)
    }
}

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
