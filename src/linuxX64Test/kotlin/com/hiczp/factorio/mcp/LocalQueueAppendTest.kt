@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalQueueAppendTest {
    @Test
    fun rejectsFieldsOutsideTheProvenElementOrOverlappingEachOther() {
        val queue = LocalQueueAppend.Proof(8, 16, 24, 16, listOf(32))
        val alt = InlineModifier(0, 8, 13, 10)
        val handler = InputModifierSources(40, 41, 42)
        MouseInputLayout(queue, 0, 8, 9, alt, 64, handler)
        assertFails { MouseInputLayout(queue.copy(extent = Int.MIN_VALUE), 0, 8, 9, alt, 64, handler) }
        assertFails { MouseInputLayout(queue, -1, 8, 9, alt, 64, handler) }
        assertFails { MouseInputLayout(queue, 9, 0, 1, alt, 64, handler) }
        assertFails { MouseInputLayout(queue, 0, 16, 9, alt, 64, handler) }
        assertFails { MouseInputLayout(queue, 0, 7, 9, alt, 64, handler) }
        assertFails { MouseInputLayout(queue, 0, 8, 8, alt, 64, handler) }
        assertFails { MouseInputLayout(queue, 0, 8, 9, alt.copy(field = 16), 64, handler) }
        assertFails { MouseInputLayout(queue, 0, 8, 9, alt.copy(field = 8), 64, handler) }
        assertFails { MouseInputLayout(queue, 0, 8, 9, alt, 42, handler) }
        assertFails { MouseInputLayout(queue, 0, 8, 9, alt, 64, handler.copy(alt = 16)) }
        assertFails { MouseInputLayout(queue.copy(cursor = 60), 0, 8, 9, alt, 64, handler) }
    }

    @Test
    fun derivesCompleteNativeElementCopiesAndQueueAssociations() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/queue_append_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val function = image.symbol("fixture_packet_append")
            val callee = image.symbol("fixture_packet_slow")
            val flow = X64ControlFlow.resolve(image, function)
            val sink = flow.instructions.single {
                it.operation == X64Instructions.Operation.CALL &&
                        (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == callee.address
            }.offset
            val proof = LocalQueueAppend(flow).at(sink, constant("fixture_owner_size"))
            assertEquals(constant("fixture_packet_size").toInt(), proof.extent)
            assertEquals(constant("fixture_queue_offset"), proof.queue)
            assertEquals(constant("fixture_cursor_offset"), proof.cursor)
            assertEquals(constant("fixture_limit_offset"), proof.limit)
            assertEquals(1, proof.commits.size)
        }
    }

    @Test
    fun rejectsIncompleteCopiesWrongPointersAndMismatchedFullQueueGuards() {
        val valid = "55 48 89 e5 48 83 ec 20 48 8b 47 10 48 8b 4f 18 48 83 c1 f0 48 39 c8 74 0e " +
                "0f 10 45 e0 0f 11 00 48 83 47 10 10 eb 0d 48 83 c7 08 48 8d 75 e0 e8 cc 01 00 00 48 83 c4 20 5d c3"

        fun analyze(code: String): LocalQueueAppend.Proof {
            val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
            val sink = flow.instructions.single { it.operation == X64Instructions.Operation.CALL }.offset
            return LocalQueueAppend(flow).at(sink, 32)
        }

        val proof = analyze(valid)
        assertEquals(LocalQueueAppend.Proof(8, 16, 24, 16, listOf(32)), proof)
        for (invalid in listOf(
            valid.replace("47 10 10", "47 10 20"), // Increment disagrees with the last-element guard.
            valid.replace("74 0e", "75 0e"), // Inverted full-queue branch.
            valid.replace("0f 11 00", "0f 11 01"), // Writes through the limit pointer.
            valid.replace("10 45 e0", "10 45 e8"), // Copies a different local interval.
            valid.replace("8b 47 10", "8b 46 10"), // Cursor from another argument.
            valid.replace("48 83 c7 08", "48 89 f7 90"), // Slow path uses another original argument.
            valid.replace("8d 75 e0", "8d 75 e8"), // Slow path consumes another local object.
        )) assertFails { analyze(invalid) }
    }
}
