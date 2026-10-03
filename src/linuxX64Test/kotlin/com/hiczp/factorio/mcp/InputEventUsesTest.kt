@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class InputEventUsesTest {
    @Test
    fun prunesOnlyKnownKindsAndRequiresEveryPackedReadByte() {
        // Event kind 7 copies a packed payload into InputState; every other kind returns without reading it.
        val code = "83 3e 07 75 08 f3 0f 6f 46 10 0f 11 07 c3"
        val header = EventHeader(48, 0, 8)
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all(128))
        val type = (0L until 4).toSet()
        val payload = (16L until 32).toSet()
        val cases = mapOf(3L to type, 7L to type + payload, 9L to type)
        val proof = InputEventUses.postUpdate(flow, header, cases)
        assertEquals(setOf(0L), proof.getValue(3).reads.map { it.offset }.toSet())
        assertEquals(proof.getValue(3), proof.getValue(9))
        assertTrue(proof.getValue(7).reads.any { it.offset == 16L && it.width == 16 })
        assertTrue(proof.values.all { it.possibleArguments.isEmpty() })
        assertFails { InputEventUses.postUpdate(flow, header, cases + (7L to (type + payload - 23L))) }
        // A state-dependent condition cannot justify pruning either branch of the Event reader.
        val unknown = X64ControlFlow(X64Instructions(machineCode(code.replace("83 3e 07", "83 3f 07"))).all(128))
        assertFails { InputEventUses.postUpdate(unknown, header, cases) }
        // Moving bytes into the borrowed Event is never a passive observation.
        val mutation = X64ControlFlow(X64Instructions(machineCode(code.replace("0f 11 07", "0f 11 06"))).all(128))
        assertFails { InputEventUses.postUpdate(mutation, header, cases) }
    }

    @Test
    fun followsPostUpdateKindsAndPreservesBorrowedEventChecks() {
        val valid = "53 48 89 f3 83 3b 07 75 0b 8b 73 10 e8 00 01 00 00 c6 00 00 5b c3"
        val update = InputStateKeyUpdate(
            16, 0, 2, 3,
            InputStateKeyUpdate.Case(3, 0, 16, 0, emptyList()),
            InputStateKeyUpdate.Case(7, 0, 16, 0, emptyList())
        )

        fun inspect(code: String) = InputEventUses.postUpdate(
            X64ControlFlow(X64Instructions(machineCode(code)).all(128)), 0x111, EventHeader(32, 0, 8), update
        )

        val proof = inspect(valid)
        assertEquals(setOf(0L), proof.getValue(3).reads.map { it.offset }.toSet())
        assertEquals(setOf(0L, 16L), proof.getValue(7).reads.map { it.offset }.toSet())
        assertTrue(proof.values.all { it.possibleArguments.isEmpty() })
        for (invalid in listOf(
            valid.replace("8b 73 10", "48 89 de"), // Pointer instead of scalar key argument.
            valid.replace("8b 73 10", "8b 73 18"), // Uninitialized payload field.
            valid.replace("8b 73 10", "48 89 1f"), // Escape through an external store.
            valid.replace("8b 73 10", "48 8d 33"), // Derived event address.
            valid.replace("83 3b 07", "83 7b 10 07"), // Code-dependent branch.
            valid.replace("75 0b", "75 fe"), // Selected cycle.
            valid.replace("e8 00 01 00 00", "48 89 de ff d0"), // Untyped indirect borrower.
            valid.replace("5b c3", "5b e9 00 01 00 00"), // Unverified tail.
        )) assertFails(invalid) { inspect(invalid) }
    }

    @Test
    fun followsCompleteSelectedCasesAndRejectsAdditionalReadsOrAddressLeaks() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/input_state_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val header = EventHeader(
                constant("fixture_event_extent").toInt(), constant("fixture_event_type"),
                constant("fixture_event_time")
            )
            val code = constant("fixture_event_code")
            val function = image.symbol("fixture_state_update")
            val tables = X64JumpTables.resolve(image, function)
            val flow = X64ControlFlow.resolve(image, function)
            fun resolve(selected: X64ControlFlow = flow) = InputEventUses.analyze(
                selected, tables.single(),
                header, code, setOf(3, 5)
            )

            val proof = resolve()
            assertEquals(setOf(3L, 5L), proof.keys)
            assertTrue(proof.getValue(5).possibleArguments.isEmpty())
            val borrow = proof.getValue(3).possibleArguments.single()
            assertEquals(image.symbol("fixture_observe_event").address - function.address, borrow.target)
            assertTrue(7 in borrow.registers)
            val call = flow.body.getValue(borrow.site)
            assertFails {
                resolve(X64ControlFlow(flow.instructions.map {
                    if (it == call) it.copy(destination = Register(0, 8)) else it
                }, tables))
            }
            assertTrue(proof.values.all { it.reads.map { read -> read.offset }.toSet() == setOf(header.type, code) })
            val read = proof.getValue(3).reads.single { it.offset == code }
            val load = flow.body.getValue(read.site)
            val source = load.source as Memory
            for (changed in listOf(
                load.copy(source = source.copy(displacement = code + 4)),
                load.copy(source = source.copy(index = 6)),
                load.copy(source = Register(6, 4))
            )) {
                assertFails {
                    resolve(
                        X64ControlFlow(
                            flow.instructions.map { if (it == load) changed else it },
                            tables
                        )
                    )
                }
            }
        }
    }
}
