@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails

class WidgetFocusEntryTest {
    @Test
    fun verifiesNativeFocusForwardingAndRejectsChangedEntryArguments() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/focus_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_widget_focus")
            val manager = image.symbol("fixture_focus_manager").address
            val size = image.symbol("fixture_focus_size").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val bytes = image.functionBytes(function, 2048)
            WidgetFocusEntry.verify(bytes, function.address, manager, size)
            assertFails { WidgetFocusEntry.verify(bytes, function.address, manager + 1, size) }
            assertFails { WidgetFocusEntry.verify(bytes, function.address, manager, 8) }
            val instruction = X64Instructions(bytes).all().single { it.destination == Register(2, 4) }
            val changed = bytes.bytes(0, bytes.size.toInt())
            // Change the source register in the compiler's register-to-register flag transfer.
            changed[instruction.offset.toInt() + instruction.size - 1] =
                (changed[instruction.offset.toInt() + instruction.size - 1].toInt() xor 1).toByte()
            assertFails { WidgetFocusEntry.verify(BinaryView(changed), function.address, manager, size) }
        }
    }
}
