@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ControlModifiersTest {
    @Test
    fun derivesVariedModifierBitsAndFieldsFromNativeChecks() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val layouts = listOf(1, 23).map { padding ->
            MappedBinary("$directory/control_modifiers_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String, offset: Long = 0, width: Int = 8): Long {
                    val symbol = image.symbol(name)
                    return image.virtualBytes(symbol.address, symbol.size).unsigned(offset, width)
                }

                fun resolve(extent: Long) = ControlModifiers.resolve(
                    image, extent, "_ZNK7Binding7matchesEv", "matches",
                    "fixture_control_down", "fixture_shift_down", "fixture_alt_down"
                )

                val layout = resolve(constant("fixture_binding_size"))
                assertEquals(constant("fixture_modifiers_field"), layout.field)
                assertEquals(
                    (0..2).map { constant("fixture_modifier_bits", it * 4L, 4).toInt() },
                    listOf(layout.control, layout.shift, layout.alt)
                )
                assertFails { resolve(layout.field) }
                val function = image.symbol("_ZNK7Binding7matchesEv")
                val flow = X64ControlFlow.resolve(image, function)
                val direct = listOf("fixture_control_down", "fixture_shift_down").map { name ->
                    val target = image.symbol(name).address - function.address
                    flow.instructions.filter { it.operation == Operation.CALL && it.destination == Immediate(target) }
                        .map { it.offset }.toSet()
                }
                val inline = DwarfInlines(image).find(function, "matches", setOf("fixture_alt_down"))
                    .flatMap { it.ranges }.map { it.start - function.address }.toSet()
                val checks = direct + listOf(inline)
                fun analyze(instructions: List<X64Instructions.Instruction>) = ControlModifiers.analyze(
                    X64ControlFlow(instructions), constant("fixture_binding_size"), checks
                )
                assertEquals(layout, analyze(flow.instructions))
                val guards = flow.instructions.filter {
                    it.operation == Operation.TEST && it.source is Immediate &&
                            (it.destination as? Register)?.width == 1
                }
                assertEquals(3, guards.size)
                for (guard in guards) {
                    val branch = flow.body.getValue(guard.offset + guard.size)
                    assertEquals(Operation.JCC, branch.operation)
                    assertFails {
                        analyze(flow.instructions.map {
                            if (it == branch) it.copy(condition = checkNotNull(it.condition) xor 1) else it
                        })
                    }
                    assertFails {
                        analyze(flow.instructions.map {
                            if (it == guard) it.copy(source = Immediate((it.source as Immediate).value or 128)) else it
                        })
                    }
                }
                layout
            }
        }
        assertNotEquals(layouts[0].field, layouts[1].field)
        assertNotEquals(layouts[0].control, layouts[1].control)
    }
}
