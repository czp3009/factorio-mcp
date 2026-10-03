@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class EventCopyCasesTest {
    @Test
    fun provesOnlyBoundedByteCopiesForSelectedTypes() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/input_state_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val header = EventHeader(
                constant("fixture_event_extent").toInt(), constant("fixture_event_type"),
                constant("fixture_event_time")
            )
            val code = constant("fixture_event_code")
            val function = image.symbol("fixture_event_copy")
            val tables = X64JumpTables.resolve(image, function)
            val flow = X64ControlFlow.resolve(image, function)
            fun resolve(
                selected: X64ControlFlow = flow, kinds: Set<Long> = setOf(3, 5),
                selectedHeader: EventHeader = header
            ) =
                EventCopyCases.analyze(selected, tables.single(), selectedHeader, code, kinds)

            val proofs = resolve()
            assertEquals(setOf(3L, 5L), proofs.keys)
            assertEquals(proofs.getValue(3).bytes, proofs.getValue(5).bytes)
            for (proof in proofs.values) {
                assertTrue(proof.reads.containsAll(proof.bytes))
                assertTrue((header.type until header.type + 4).all { it in proof.reads })
                assertTrue((header.time until header.time + 8).all { it in proof.reads })
                assertTrue(proof.reads.all { it in 0 until header.extent })
            }
            assertTrue((code until code + 4).all { it in proofs.getValue(3).bytes })
            assertTrue(proofs.getValue(3).path.any { flow.body.getValue(it).operation == Operation.VECTOR_MOV })
            val complete = proofs.getValue(3)
            val required = listOf(header.type to 4, header.time to 8, code to 4)
                .flatMap { (offset, width) -> (offset until offset + width).toList() }.toSet()
            val unused = (0L until header.extent).first { it !in complete.reads && it !in complete.bytes }
            val initialized = complete.reads + unused
            val selectedCase = EventCopyCases.Case(required, initialized)
            assertEquals(complete, EventCopyCases.analyze(flow, tables.single(), header, mapOf(3L to selectedCase))[3])
            // Extra initialized padding need not be copied, but every actual scalar/vector source byte must exist.
            val payloadByte = complete.reads.first { it !in required }
            assertFails {
                EventCopyCases.analyze(flow, tables.single(), header,
                    mapOf(3L to selectedCase.copy(initialized = initialized - payloadByte)))
            }
            assertFails {
                EventCopyCases.analyze(flow, tables.single(), header,
                    mapOf(3L to selectedCase.copy(required = required + unused)))
            }
            assertFails { resolve(kinds = setOf(1, 3)) }
            assertFails { resolve(kinds = setOf(0, 5)) }
            assertFails { resolve(selectedHeader = header.copy(extent = code.toInt())) }
            val arguments = SysVArgumentFlow(flow)
            val store = flow.instructions.single { instruction ->
                instruction.operation == Operation.MOV && (instruction.destination as? Memory)?.let {
                    arguments.memory(instruction.offset, it) ==
                            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, code), 4)
                } == true && instruction.offset in proofs.getValue(3).path
            }
            val destination = store.destination as Memory
            for (changed in listOf(
                store.copy(destination = destination.copy(base = 6)),
                store.copy(destination = destination.copy(displacement = destination.displacement + 1)),
                store.copy(source = Register(6, 8)), store.copy(operation = Operation.ADD)
            )) {
                val modified = X64ControlFlow(flow.instructions.map { if (it == store) changed else it }, tables)
                assertFails { resolve(selected = modified) }
            }
            val pop = proofs.getValue(3).path.map { flow.body.getValue(it) }.first { it.operation == Operation.POP }
            assertFails {
                resolve(selected = X64ControlFlow(flow.instructions.map {
                    if (it == pop) it.copy(destination = Register(7, 8)) else it
                }, tables))
            }
        }
    }
}
