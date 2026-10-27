package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class DwarfCallFrameTest {
    private fun bytes(vararg values: Int) = BinaryView(values.map { it.toByte() }.toByteArray())

    private fun frame(vararg instructions: Int) = EhFrames.Frame(
        100, 200,
        EhFrames.Common(0, 1, -8, 16, 0x1b, true, bytes(0x0c, 7, 8, 0x90, 1)),
        bytes(*instructions),
    )

    @Test
    fun followsRspAndRbpRowsAndRestoresTheWholeRememberedState() {
        val frame = frame(
            0x41, 0x0e, 16, 0x86, 2,
            0x43, 0x0d, 6,
            0x0a,
            0x44, 0x0c, 7, 8, 0xc6,
            0x42, 0x0b,
        )
        val registers = mapOf(7 to 1000L, 6 to 1016L, 16 to 108L, 3 to 77L, 0 to 99L)
        val memory = mapOf(1000L to 111L, 1008L to 112L, 1016L to 113L, 1024L to 114L)
        fun caller(pc: Long) = DwarfCallFrame.caller(frame, pc, registers) { memory.getValue(it) }
        assertEquals(1008L, caller(100).getValue(7))
        assertEquals(111L, caller(100).getValue(16))
        assertEquals(1016L, caller(101).getValue(7))
        assertEquals(111L, caller(101).getValue(6))
        assertEquals(1032L, caller(104).getValue(7))
        assertEquals(114L, caller(104).getValue(16))
        assertEquals(1008L, caller(108).getValue(7))
        assertEquals(1016L, caller(108).getValue(6))
        assertEquals(1032L, caller(110).getValue(7))
        assertEquals(113L, caller(110).getValue(6))
        assertEquals(77L, caller(110).getValue(3))
        assertNull(caller(110)[0])
    }

    @Test
    fun respectsUndefinedRegisterAndValueRules() {
        val registers = mapOf(7 to 1024L, 16 to 100L, 3 to 77L)
        val result = DwarfCallFrame.caller(frame(0x09, 6, 3, 0x14, 12, 2, 0x07, 16), 100, registers) {
            error("Undefined return address must not be read")
        }
        assertEquals(77L, result[6])
        assertEquals(1016L, result[12])
        assertNull(result[16])
    }

    @Test
    fun followsPltCfaExpressionsBeforeAndAfterTheResolverPush() {
        val expression = intArrayOf(0x77, 8, 0x80, 0, 0x3f, 0x1a, 0x3b, 0x2a, 0x33, 0x24, 0x22)
        val frame = frame(0x0f, expression.size, *expression)
        for ((pc, stack) in listOf(100L to 1000L, 107L to 992L)) {
            val caller = DwarfCallFrame.caller(frame, pc, mapOf(7 to stack, 16 to pc)) {
                assertEquals(1000L, it)
                9000L
            }
            assertEquals(1008L, caller[7])
            assertEquals(9000L, caller[16])
        }
        // A different register/offset expression survives remember/restore, while def_cfa replaces it.
        val changed = frame(0x0f, 3, 0x92, 6, 16, 0x0a, 0x41, 0x0c, 7, 32, 0x41, 0x0b)
        val registers = mapOf(6 to 1016L, 7 to 1008L, 16 to 100L)
        fun caller(pc: Long) = DwarfCallFrame.caller(changed, pc, registers) { 9000L }
        assertEquals(1032L, caller(100)[7])
        assertEquals(1040L, caller(101)[7])
        assertEquals(1032L, caller(102)[7])
    }

    @Test
    fun rejectsMalformedOrUnboundedCfaExpressions() {
        fun reject(vararg expression: Int) {
            var reads = 0
            assertFails {
                DwarfCallFrame.caller(frame(0x0f, expression.size, *expression), 100, mapOf(7 to 1000L, 16 to 100L)) {
                    reads++
                    9000L
                }
            }
            assertEquals(0, reads, "Invalid CFA must not read stack memory")
        }
        reject()
        reject(0x1a)
        reject(0x31, 0x32)
        reject(0x78, 8)
        reject(0x31, 0x10, 64, 0x24)
        reject(0x32, 0x10, 62, 0x24)
        reject(0x28)
        reject(*IntArray(65) { 0x30 })
    }

    @Test
    fun rejectsUnsafeRowsUnsupportedProgramsAndArithmeticOverflow() {
        val registers = mapOf(7 to 1024L, 16 to 100L)
        fun reject(frame: EhFrames.Frame, pc: Long = 100) = assertFails {
            DwarfCallFrame.caller(frame, pc, registers) { 123 }
        }
        reject(frame(0x0b))
        reject(frame(0x0c, 7, 0))
        reject(frame(0x0c, 6, 8))
        reject(frame(0x0f))
        reject(frame(0x02, 255))
        reject(frame(), 200)
        reject(frame().let { it.copy(common = it.common.copy(signalFrame = true)) })
        reject(frame(0x85, 2).let { it.copy(common = it.common.copy(dataAlignment = Long.MAX_VALUE)) })
    }
}
