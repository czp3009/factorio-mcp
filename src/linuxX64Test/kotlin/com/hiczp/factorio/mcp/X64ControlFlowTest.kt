package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class X64ControlFlowTest {
    @Test
    fun dominanceUsesEveryEntryPathIncludingLoopsAndBypasses() {
        val flow = X64ControlFlow(X64Instructions(machineCode("85 ff 74 03 90 eb f9 c3")).all())
        assertTrue(flow.dominates(0, 7))
        assertTrue(flow.dominates(2, 7))
        assertTrue(flow.dominates(4, 5))
        assertFalse(flow.dominates(4, 7))
        assertFails { flow.dominates(3, 7) }
    }

    @Test
    fun specializesOnlyExactByteReadsAndOriginalConditionalEdges() {
        val original =
            X64ControlFlow(X64Instructions(machineCode("0f b6 07 83 f8 02 75 01 90 c3")).all())
        val same = original.withByteValue(0, 2)
        assertEquals(
            0L,
            ScalarExpression.evaluate(ScalarExpression(same).branch(6)) { error("No input") },
        )
        val other = original.withByteValue(0, 5)
        assertEquals(
            1L,
            ScalarExpression.evaluate(ScalarExpression(other).branch(6)) { error("No input") },
        )
        assertEquals(setOf(0L, 3L, 6L, 9L), other.following(mapOf(6L to 9L)).reachable)
        val selected = same.following(mapOf(6L to 8L)).reaching(8)
        assertEquals(setOf(0L, 3L, 6L, 8L), selected.reachable)
        assertEquals(listOf(8L), selected.successors[6])
        assertFails { original.withByteValue(3, 2) }
        assertFails { original.withByteValue(0, 256) }
        assertFails { original.following(mapOf(6L to 3L)) }
        assertFails { original.following(mapOf(3L to 6L)) }
        assertFails { same.following(mapOf(6L to 8L)).following(mapOf(6L to 9L)) }
    }

    @Test
    fun treatsSelfRecursionAsAnAbiCallAndRejectsInteriorCalls() {
        val code = "48 89 fb e8 f8 ff ff ff 48 89 df c3"
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        assertEquals(listOf(8L), flow.successors[3])
        val arguments = SysVArgumentFlow(flow)
        assertNull(arguments.register(8, 7))
        assertEquals(SysVArgumentFlow.Reference(7), arguments.register(11, 7))
        assertEquals(
            emptyList(),
            X64JumpTables.resolve(flow.instructions, 0x1000) { _, _ -> error("No table") },
        )
        assertFails {
            X64ControlFlow(
                X64Instructions(machineCode(code.replace("f8 ff ff ff", "f9 ff ff ff"))).all()
            )
        }
        assertFails {
            X64JumpTables.resolve(
                X64Instructions(machineCode(code.replace("f8 ff ff ff", "f9 ff ff ff"))).all(),
                0x1000,
            ) { _, _ ->
                error("No table")
            }
        }
    }

    @Test
    fun selectedProvenanceKeepsEveryIncomingPathAndLaterBackedge() {
        // TEST; JE alternate; target NOP; JMP back to TEST; alternate RET.
        val flow = X64ControlFlow(X64Instructions(machineCode("85 ff 74 03 90 eb f9 c3")).all())
        assertEquals(setOf(0L, 2L, 4L, 5L, 7L), flow.reachable)
        val selected = flow.reaching(4)
        assertEquals(setOf(0L, 2L, 4L, 5L), selected.reachable)
        assertEquals(setOf(5L), selected.predecessors[0])
        assertEquals(listOf(0L), selected.successors[5])
        assertFails { flow.reaching(3) }
    }
}
