@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InputStateKeyUpdateTest {
    @Test
    fun derivesKeyCasesAndReturnedStateStoresFromCompilerLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/key_update_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) =
                image.symbol("fixture_key_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val function = image.symbol("fixture_key_update")
            val tables = X64JumpTables.resolve(image, function)
            val instructions = X64Instructions(image.functionBytes(function, 32768)).all(8192)
            val flow = X64ControlFlow(instructions, tables)
            val lookup = image.symbol("fixture_key_lookup").address - function.address
            val header = EventHeader(constant("event_size").toInt(), constant("event_type"), constant("event_time"))
            val size = constant("owner_size")
            val result = InputStateKeyUpdate.analyze(flow, tables.single(), lookup, size, header)
            val uses = InputEventUses.analyze(
                flow, tables.single(), header, result.code,
                setOf(result.press.kind, result.release.kind)
            )
            assertEquals(setOf(header.type, result.code), uses.values.flatMap { it.reads }.map { it.offset }.toSet())
            assertEquals(constant("press"), result.press.kind)
            assertEquals(constant("release"), result.release.kind)
            assertEquals(constant("event_code"), result.code)
            assertEquals(constant("map"), result.map)
            assertEquals(constant("held"), result.held)
            assertEquals(constant("blocked") + 1, result.requiredValueSize)
            assertEquals(
                setOf(constant("held") to 1, constant("used") to 8, constant("blocked") to 1),
                result.release.stores.map { it.offset to it.width }.toSet()
            )
            assertFails { InputStateKeyUpdate.analyze(flow, tables.single(), lookup + 1, size, header) }
            assertFails { InputStateKeyUpdate.analyze(flow, tables.single(), lookup, result.map, header) }
            assertFails {
                InputStateKeyUpdate.analyze(
                    flow,
                    tables.single(),
                    lookup,
                    size,
                    header.copy(type = header.time)
                )
            }
            fun rejects(changed: X64Instructions.Instruction) {
                val modified =
                    X64ControlFlow(instructions.map { if (it.offset == changed.offset) changed else it }, tables)
                assertFails { InputStateKeyUpdate.analyze(modified, tables.single(), lookup, size, header) }
            }

            val held = flow.body.getValue(result.press.stores.single().site)
            rejects(held.copy(source = Immediate(0)))
            rejects(held.copy(destination = (held.destination as Memory).copy(displacement = 256)))
            val code = flow.instructions.single { instruction ->
                instruction.offset < result.press.lookup && instruction.operation == Operation.MOV &&
                        instruction.destination == Register(6, 4) &&
                        SysVArgumentFlow(flow).source(instruction.offset)?.reference == SysVArgumentFlow.Reference(
                    6,
                    result.code
                )
            }
            rejects(code.copy(destination = Register(2, 4)))
            rejects(code.copy(source = (code.source as Memory).copy(width = 1), operation = Operation.MOVZX))
            val released = flow.body.getValue(result.release.stores.first { it.offset == result.held }.site)
            rejects(released.copy(source = Immediate(1)))
            val post = image.symbol("fixture_key_post")
            val postFlow = X64ControlFlow.resolve(image, post)
            val postLookup = image.symbol("fixture_key_lookup").address - post.address
            val paths = KeyPostUpdate.analyze(postFlow, postLookup, header, result)
            assertEquals(emptyList(), paths.getValue(result.press.kind).stores)
            val clearing = paths.getValue(result.release.kind)
            assertEquals(setOf(0L, 1L), clearing.stores.map { it.offset }.toSet())
            assertEquals(2, clearing.lookups.size)
            val store = postFlow.body.getValue(clearing.stores.first().site)
            val corrupted = X64ControlFlow(postFlow.instructions.map {
                if (it == store) it.copy(destination = (store.destination as Memory).copy(displacement = result.held)) else it
            })
            assertFails { KeyPostUpdate.analyze(corrupted, postLookup, header, result) }
            assertFails { KeyPostUpdate.analyze(postFlow, postLookup + 1, header, result) }
            val getter = image.symbol("fixture_key_modifier")
            val getterFlow = X64ControlFlow.resolve(image, getter)
            val getterLookup = image.symbol("fixture_key_lookup").address - getter.address
            fun modifier(
                selected: X64ControlFlow = getterFlow, map: Long = result.map, held: Long = result.held,
                clear: Set<Long> = setOf(0, 1)
            ) =
                ModifierKey.analyze(selected, setOf(getterLookup), map, held, clear)

            val modifierProof = modifier()
            assertEquals(constant("modifier_code"), modifierProof.code)
            assertEquals(setOf(1L), modifierProof.clear)
            assertFails { modifier(map = result.map + 1) }
            assertFails { modifier(held = result.held + 1) }
            assertFails { modifier(clear = setOf(0)) }
            val getterCall = getterFlow.instructions.first { it.operation == Operation.CALL }
            assertFails {
                modifier(X64ControlFlow(getterFlow.instructions.map {
                    if (it == getterCall) it.copy(destination = Immediate(getterLookup + 1)) else it
                }))
            }
        }
    }
}
