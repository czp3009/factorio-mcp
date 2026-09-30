@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class NamedPointerRegistryTest {
    @Test
    fun resolvesIndependentNativeNamesAndRejectsIncorrectBounds() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val layouts = listOf(1, 23).map { padding ->
            MappedBinary("$directory/named_registry_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String): Long {
                    val symbol = image.symbol(name)
                    return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
                }

                val size = constant("fixture_registry_entry_size")
                val name = constant("fixture_registry_name_offset")
                fun resolve(extent: Long) = NamedPointerRegistry.resolve(
                    image, "fixture_find_name", "fixture_registry",
                    "fixture_registry_ready", extent
                )

                val layout = resolve(size)
                assertEquals(name, layout.nameData)
                assertEquals(name + 8, layout.nameSize) // This fixture uses the host libstdc++ string ABI.
                assertNotEquals(layout.begin, layout.end)
                assertFails { resolve(name + 8) }
                val function = image.symbol("fixture_find_name")
                val owner = image.symbol("fixture_registry")
                val guard = image.symbol("fixture_registry_ready")
                val instructions = X64Instructions(image.functionBytes(function, 4096)).all(1024)
                val comparison = instructions.filter { it.operation == Operation.CALL }.map {
                    (it.destination as Immediate).value + function.address
                }.single { image.importedFunction(it) in setOf("bcmp", "memcmp") }

                fun analyze(
                    body: List<X64Instructions.Instruction>, ready: Long = guard.address,
                    compare: Long = comparison
                ) = NamedPointerRegistry.analyze(
                    X64ControlFlow(body),
                    function.address, owner.address, owner.size, ready, size, compare
                )
                assertEquals(layout, analyze(instructions))
                assertFails { analyze(instructions, ready = guard.address + 1) }
                assertFails { analyze(instructions, compare = comparison + 1) }
                for (branch in instructions.filter { it.operation == Operation.JCC }) {
                    assertFails("Reversed registry branch at ${branch.offset}") {
                        analyze(instructions.map {
                            if (it == branch) it.copy(condition = checkNotNull(it.condition) xor 1) else it
                        })
                    }
                }
                val advance = instructions.single {
                    it.operation == Operation.ADD && it.source == Immediate(8) &&
                            it.destination != Register(4, 8)
                }
                assertFails {
                    analyze(instructions.map { if (it == advance) it.copy(source = Immediate(16)) else it })
                }
                layout
            }
        }
        assertNotEquals(layouts[0].nameData, layouts[1].nameData)
    }
}
