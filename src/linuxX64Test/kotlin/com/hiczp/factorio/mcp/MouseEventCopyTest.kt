@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class MouseEventCopyTest {
    @Test
    fun derivesCompiledEventArgumentsAndRejectsMismatchedParentsAndSlots() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/mouse_event_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val table = ItaniumVtable.resolve(image, "_ZTV6Widget")
            val x = table.method(image, "_ZNK6Widget8paddingXEv").slot
            val y = table.method(image, "_ZNK6Widget8paddingYEv").slot
            val function = image.symbol("_ZNK10MouseEvent8relativeEP6Widget")
            val size = scalar("fixture_widget_size")
            val parent = scalar("fixture_parent")
            val proof = MouseEventCopy.resolve(image, function, size, parent, x, y)
            assertEquals(
                MouseEventCopy.Proof(
                    scalar("fixture_event_size").toInt(), scalar("fixture_source"),
                    scalar("fixture_x"), scalar("fixture_y")
                ), proof
            )
            assertFails { MouseEventCopy.resolve(image, function, size, parent + 8, x, y) }
            assertFails { MouseEventCopy.resolve(image, function, size, parent, x + 64, y) }
            assertFails { MouseEventCopy.resolve(image, function, parent + 7, parent, x, y) }
            val original = image.functionBytes(function, 4096)
            val code = original.bytes(0, original.size.toInt())
            for (prefix in listOf(
                "48 89 ca", // Foreign fourth argument as the replacement Widget.
                "48 89 f7", // Writes redirected into the source event.
                "48 89 fe", // Reads redirected into uninitialized output storage.
                "48 31 db", // Damage a callee-saved register before the native prologue.
                "e8 00 00 00 00", // An unproved helper call.
                "4c 8b 96 f8 00 00 00", // Extra read beyond the compiled event extent.
            )) {
                val first = machineCode(prefix)
                assertFails(prefix) {
                    MouseEventCopy.analyze(
                        BinaryView(first.bytes(0, first.size.toInt()) + code),
                        size, parent, x, y
                    )
                }
            }
            val guard = X64Instructions(original).all(1024).first { it.operation == X64Instructions.Operation.JCC }
            val reversed = code.copyOf()
            val condition = guard.offset.toInt() + if (reversed[guard.offset.toInt()] == 0x0f.toByte()) 1 else 0
            reversed[condition] = (reversed[condition].toInt() xor 1).toByte()
            assertFails { MouseEventCopy.analyze(BinaryView(reversed), size, parent, x, y) }
            val parentLoad = X64Instructions(original).all(1024).last {
                val source = it.source as? X64Instructions.Memory
                it.operation == X64Instructions.Operation.MOV && it.destination is X64Instructions.Register &&
                        source != null && source.width == 8 && source.displacement == parent && source.index == null && !source.relative
            }
            val destination = (parentLoad.destination as X64Instructions.Register).number
            val source = checkNotNull((parentLoad.source as X64Instructions.Memory).base)
            val stalled = code.copyOf()
            repeat(parentLoad.size) { stalled[parentLoad.offset.toInt() + it] = 0x90.toByte() }
            // Replace the next-parent load with a copy of the current, already checked Widget.
            stalled[parentLoad.offset.toInt()] =
                (0x48 or (if (source >= 8) 4 else 0) or (if (destination >= 8) 1 else 0)).toByte()
            stalled[parentLoad.offset.toInt() + 1] = 0x89.toByte()
            stalled[parentLoad.offset.toInt() + 2] = (0xc0 or ((source and 7) shl 3) or (destination and 7)).toByte()
            val failure = assertFails { MouseEventCopy.analyze(BinaryView(stalled), size, parent, x, y) }
            assertTrue(failure.message.orEmpty().contains("freshly loaded parent"))
        }
    }
}
