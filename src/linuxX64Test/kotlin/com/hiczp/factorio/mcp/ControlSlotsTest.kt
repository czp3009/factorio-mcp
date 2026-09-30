@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ControlSlotsTest {
    @Test
    fun classifiesReorderedAndPaddedBindingsFromNativeReaders() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val layouts = listOf(1, 23).map { padding ->
            MappedBinary("$directory/control_slots_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String, offset: Long = 0): Long {
                    val symbol = image.symbol(name)
                    return image.virtualBytes(symbol.address, symbol.size).unsigned(offset, 8)
                }

                fun resolve(extent: Long) = ControlSlots.resolve(
                    image, extent,
                    "_ZNK7Control6hasKeyEi", "hasKey", "activeValues", "_ZNK7Control6sticksEv",
                    "fixture_stick_values", "fixture_global"
                )

                val layout = resolve(constant("fixture_control_size"))
                assertEquals((0..1).map { constant("fixture_control_slots", it * 8L) }, layout.values)
                assertEquals(constant("fixture_binding_type"), layout.type)
                assertEquals(constant("fixture_binding_code"), layout.code)
                assertEquals(19L, layout.keyboardType)
                assertFails { resolve(layout.values.max() + layout.code) }
                val entry = image.symbol("_ZNK7Control6hasKeyEi")
                val bytes = image.functionBytes(entry, 4096)
                val global = image.symbol("fixture_global").address
                val ranges = DwarfInlines(image).find(entry, "hasKey", setOf("activeValues"))
                    .flatMap { it.ranges }.map { (it.start - entry.address) until (it.end - entry.address) }

                fun analyze(code: BinaryView = bytes, root: Long = global, scope: List<LongRange> = ranges) =
                    ControlSlots.analyze(code, entry.address, root, constant("fixture_control_size"), scope)
                assertFails { analyze(root = global + 8) }
                assertFails { analyze(scope = emptyList()) }
                val selections = X64Instructions(bytes).all(1024).filter { it.operation == Operation.CMOV }
                assertEquals(2, selections.size)
                for (selection in selections) {
                    val changed = bytes.bytes(0, bytes.size.toInt())
                    // Register-to-register CMOVcc ends with its condition opcode and ModRM byte.
                    val opcode = (selection.offset + selection.size - 2).toInt()
                    assertEquals(0x40 or checkNotNull(selection.condition), changed[opcode].toInt() and 255)
                    changed[opcode] = 0x46 // Unsigned-below-or-equal is not the native zero/nonzero mode selection.
                    assertFails { analyze(BinaryView(changed)) }
                }
                layout
            }
        }
        assertNotEquals(layouts[0].values, layouts[1].values)
        assertNotEquals(layouts[0].type, layouts[1].type)
    }
}
