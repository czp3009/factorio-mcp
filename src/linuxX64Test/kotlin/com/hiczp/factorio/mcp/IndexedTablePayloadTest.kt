@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class IndexedTablePayloadTest {
    @Test
    fun associatesCompiledLookupWithTypedQueueElementHeaderAndPayload() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/table_payload_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val function = image.symbol("fixture_table_append")
            val flow = X64ControlFlow.resolve(image, function)
            val table = GuardedByteTable.resolve(image, function).single()
            val target = image.symbol("fixture_table_grow")
            val calls = flow.instructions.filter {
                it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) == target.address
            }.map { it.offset }.toSet()
            val header = EventHeader(
                constant("fixture_payload_extent").toInt(), constant("fixture_payload_type"),
                constant("fixture_payload_time")
            )
            val extent = constant("fixture_packet_extent")
            val result = IndexedTablePayload.analyze(flow, table, header, mapOf(6 to extent), calls)
            assertEquals(constant("fixture_payload_code"), result.code)
            assertEquals(SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, 0), 8), result.queue)
            assertEquals(
                setOf(constant("fixture_packet_type")),
                ScalarExpression.inputs(result.kind).map { it.field.reference.offset }.toSet()
            )
            for (type in 0..31) assertEquals(
                if (type == padding) 8L else 7L,
                ScalarExpression.evaluate(result.kind) { type.toLong() })
            assertFails {
                IndexedTablePayload.analyze(
                    flow,
                    table,
                    header.copy(extent = header.extent + 8),
                    mapOf(6 to extent),
                    calls
                )
            }
            assertFails {
                IndexedTablePayload.analyze(
                    flow,
                    table,
                    header.copy(type = header.time),
                    mapOf(6 to extent),
                    calls
                )
            }
            assertFails { IndexedTablePayload.analyze(flow, table, header, mapOf(7 to extent), calls) }
            assertFails { IndexedTablePayload.analyze(flow, table, header, mapOf(6 to extent), emptySet()) }
            val store = flow.body.getValue(result.store)
            val memory = store.destination as Memory
            val typeStore = flow.instructions.single {
                it.destination == memory.copy(displacement = header.type)
            }
            val timeStore = flow.instructions.single {
                it.destination == memory.copy(displacement = header.time, width = 8)
            }

            fun rejects(changed: X64Instructions.Instruction) {
                val modified =
                    X64ControlFlow(flow.instructions.map { if (it.offset == changed.offset) changed else it })
                assertFails { IndexedTablePayload.analyze(modified, table, header, mapOf(6 to extent), calls) }
            }
            rejects(store.copy(source = Register(0, 4)))
            rejects(store.copy(destination = memory.copy(scale = if (memory.scale == 1) 2 else 1)))
            rejects(typeStore.copy(destination = (typeStore.destination as Memory).copy(width = 2)))
            rejects(timeStore.copy(destination = (timeStore.destination as Memory).copy(displacement = header.time + 1)))
            rejects(typeStore.copy(source = Register(1, 4)))
            val prologue = flow.instructions.single {
                it.operation == Operation.MOV && it.destination == Register(5, 8) && it.source == Register(4, 8)
            }
            val clobbered = X64ControlFlow(flow.instructions.map {
                if (it == prologue) it.copy(destination = Register(0, 8)) else it
            })
            assertFails {
                GuardedByteTable.analyze(clobbered, function.address) { address, size ->
                    image.virtualBytes(
                        address,
                        size
                    )
                }
            }
        }
    }
}
