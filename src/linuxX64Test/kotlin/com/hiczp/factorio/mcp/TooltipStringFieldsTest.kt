package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TooltipStringFieldsTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("destroy", 0, 1, 2, 1))

    private fun fixture(title: Long, text: Long): List<Instruction> {
        val body = mutableListOf<Instruction>()
        fun op(operation: Operation, destination: Operand? = null, source: Operand? = null) {
            body += Instruction(body.size.toLong(), 1, operation, destination, source)
        }
        fun r(number: Int) = Register(number, 8)
        fun m(base: Int, offset: Long) = Memory(base, null, 1, offset, 8)
        op(Operation.MOV, r(12), r(6))
        op(Operation.MOV, r(15), m(12, string.length))
        op(Operation.CALL, Immediate(1000))
        op(Operation.MOV, r(14), r(0))
        op(Operation.MOV, r(7), m(14, title))
        op(Operation.MOV, r(6), m(12, string.data))
        op(Operation.MOV, r(2), r(15))
        op(Operation.CALL, Immediate(2000))
        op(Operation.CMP, m(14, text + string.length), Immediate(0))
        op(Operation.CALL, Immediate(3000))
        op(Operation.MOV, r(3), r(0))
        op(Operation.LEA, r(7), m(3, title + string.local))
        op(Operation.MOV, m(3, title), r(7))
        op(Operation.LEA, r(7), m(3, text + string.local))
        op(Operation.MOV, m(3, text), r(7))
        op(Operation.RET)
        return body
    }

    private fun analyze(body: List<Instruction>, size: Long = 256): TooltipStringFields {
        val flow = X64ControlFlow(body)
        return TooltipStringFields.analyze(flow, 2, setOf(7), size, string).also {
            it.verifyInitialization(flow, 9, string)
        }
    }

    @Test
    fun resolvesReorderedAndRelocatedStringsFromTheirUses() {
        for ((title, text) in listOf(8L to 40L, 40L to 8L, 136L to 184L)) {
            assertEquals(TooltipStringFields(title, text), analyze(fixture(title, text)))
        }
    }

    @Test
    fun rejectsUnrelatedSourcesPartialReadsAndUninitializedStorage() {
        val body = fixture(8, 40)
        val replacements =
            listOf(
                body[1].copy(source = Memory(12, null, 1, 16, 8)),
                body[3].copy(source = Register(7, 8)),
                body[4].copy(source = Memory(14, null, 1, 8, 4)),
                body[8].copy(source = Immediate(1)),
                body[10].copy(source = Register(7, 8)),
                body[13].copy(source = Memory(3, null, 1, 57, 8)),
            )
        for (changed in replacements) assertFails {
            analyze(body.map { if (it.offset == changed.offset) changed else it })
        }
        assertFails { analyze(body, 71) }
        assertFails { analyze(fixture(8, 24)) }
    }
}
