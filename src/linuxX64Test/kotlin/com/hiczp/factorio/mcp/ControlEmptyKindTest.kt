@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ControlEmptyKindTest {
    @Test
    fun resolvesEmptySavedBindingsWithVariedDiscriminatorsAndFields() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val kinds = listOf(1, 23).map { padding ->
            MappedBinary("$directory/control_empty_kind_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) = image.symbol("fixture_$name").let {
                    image.virtualBytes(it.address, 8).unsigned(0, 8)
                }

                val extent = constant("size")
                val type = constant("type")
                val code = constant("code")
                val kind = constant("kind").toInt()
                val entry = image.symbol("_ZNK5Value4saveB5cxx11Ev")
                val string = NativeStringLayout(
                    constant("string_data"), constant("string_length"),
                    constant("string_local"), constant("string_size"), entry
                )
                val debug = DwarfInlines(image)
                assertEquals(kind, ControlEmptyKind.resolve(image, extent, type, code, string, debug, entry.name))
                assertFails { ControlEmptyKind.resolve(image, extent, type + 1, code, string, debug, entry.name) }
                val tables = X64JumpTables.resolve(image, entry)
                val flow = X64ControlFlow.resolve(image, entry)
                val ranges = debug.find(entry, "save", setOf("basic_string"))
                    .filter { it.ranges.size == 1 }.map { it.ranges.single() }
                    .map { DwarfRanges.Range(it.start - entry.address, it.end - entry.address) }
                val extended = ranges.map { range ->
                    val next = flow.body[range.end]
                    if (next?.operation == Operation.JMP) range.copy(end = range.end + next.size) else range
                }
                assertEquals(kind, ControlEmptyKind.analyze(flow, tables, extent, type, code, string, extended))
                fun analyze(
                    instructions: List<X64Instructions.Instruction>,
                    selected: List<X64JumpTables.Table> = tables
                ) =
                    ControlEmptyKind.analyze(
                        X64ControlFlow(instructions, selected), selected,
                        extent, type, code, string, ranges
                    )
                assertFails {
                    analyze(flow.instructions.map {
                        if (it.operation == Operation.MOV && it.destination is Memory &&
                            it.destination.width == 1 && it.source == Immediate(0)
                        ) it.copy(source = Immediate(1)) else it
                    })
                }
                assertFails {
                    analyze(flow.instructions.map {
                        if (it.operation == Operation.ADD && it.destination == Register(4, 8))
                            it.copy(
                                operation = Operation.MOV, destination = Memory(3, null, 1, string.length, 8),
                                source = Immediate(1)
                            ) else it
                    })
                }
                val table = tables.single()
                assertFails {
                    analyze(flow.instructions, listOf(table.copy(targets = table.targets.mapIndexed { index, target ->
                        if (index == kind) table.targets.first { it != target } else target
                    })))
                }
                // The same empty constructor must not be accepted for two different native types.
                assertFails {
                    analyze(flow.instructions, listOf(table.copy(targets = table.targets.mapIndexed { index, target ->
                        if (index == (kind + 1) % table.targets.size) table.targets[kind] else target
                    })))
                }
                kind
            }
        }
        assertNotEquals(kinds[0], kinds[1])
    }
}
