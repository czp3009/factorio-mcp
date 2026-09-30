package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

internal fun machineCode(text: String) = BinaryView(text.split(' ').map { it.toInt(16).toByte() }.toByteArray())

class X64InstructionsTest {
    @Test
    fun decodesScalarComparisonsWithTheirOperandWidth() {
        for ((prefix, width) in listOf("" to 4, "66 " to 8)) {
            val register = X64Instructions(machineCode("${prefix}45 0f 2e c1")).decode(0)
            assertEquals(Operation.SCALAR_COMPARE, register.operation)
            assertEquals(Register(24, width), register.destination)
            assertEquals(Register(25, width), register.source)
            val memory = X64Instructions(machineCode("${prefix}41 0f 2e 44 24 10")).decode(0)
            assertEquals(Memory(12, null, 1, 16, width), memory.source)
        }
        for (invalid in listOf("0f 2e", "48 0f 2e c1", "66 48 0f 2e c1", "f3 0f 2e c1"))
            assertFails { X64Instructions(machineCode(invalid)).all() }
        val flow = X64ControlFlow(X64Instructions(machineCode("83 f8 00 0f 2e c1 0f 44 c1 c3")).all())
        assertFails { ScalarExpression(flow).before(6, Register(0, 4)) }
    }

    @Test
    fun decodesScalarFloatLoadsStoresAndRegisterCopies() {
        val code = "f3 41 0f 10 81 10 03 00 00 f3 0f 11 45 f0 f3 45 0f 10 c1"
        val decoded = X64Instructions(machineCode(code)).all()
        assertEquals(listOf(9, 5, 5), decoded.map { it.size })
        assertEquals(
            listOf(Operation.SCALAR_MOV, Operation.SCALAR_MOV, Operation.SCALAR_MOV),
            decoded.map { it.operation })
        assertEquals(Register(16, 4), decoded[0].destination)
        assertEquals(Memory(9, null, 1, 784, 4), decoded[0].source)
        assertEquals(Memory(5, null, 1, -16, 4), decoded[1].destination)
        assertEquals(Register(16, 4), decoded[1].source)
        assertEquals(Register(24, 4), decoded[2].destination)
        assertEquals(Register(25, 4), decoded[2].source)
        for (invalid in listOf("f3 0f 10", "f3 0f 11 45", "66 f3 0f 10 c0", "f3 48 0f 10 c0"))
            assertFails { X64Instructions(machineCode(invalid)).all() }
    }

    @Test
    fun requiresExplicitByteCompareExchangeEffects() {
        val bytes = machineCode("f0 0f b0 4f 20")
        assertFails { X64Instructions(bytes).all() }
        val instruction = X64Instructions(bytes, allowByteCompareExchange = true).decode(0)
        assertEquals(Operation.BYTE_COMPARE_EXCHANGE, instruction.operation)
        assertEquals(Memory(7, null, 1, 32, 1), instruction.destination)
        assertEquals(Register(1, 1), instruction.source)
        val extended = X64Instructions(machineCode("f0 45 0f b0 4c 24 10"), allowByteCompareExchange = true).decode(0)
        assertEquals(Memory(12, null, 1, 16, 1), extended.destination)
        assertEquals(Register(9, 1), extended.source)
        for (invalid in listOf(
            "0f b0 4f 20", "f0 0f b1 4f 20", "f0 0f b0 c1", "f0 66 0f b0 4f 20",
            "f0 48 0f b0 4f 20", "f0 0f b0", "f0 0f b0 67 20", "f0 2e 0f b0 4f 20"
        )) {
            assertFails { X64Instructions(machineCode(invalid), allowByteCompareExchange = true).all() }
        }
        val flow = X64ControlFlow(
            X64Instructions(
                machineCode("48 89 f0 f0 0f b0 4f 20 48 8b 10 c3"),
                allowByteCompareExchange = true
            ).all()
        )
        assertFails { SysVArgumentFlow(flow) }
        assertFails { SysVLocalArgument(flow) }
        assertFails { X64JumpTables.resolve(flow.instructions, 0) { _, _ -> error("Unexpected table read") } }
        val arguments = SysVArgumentFlow(flow, allowByteCompareExchange = true)
        assertEquals(null, arguments.source(8))
        assertEquals(SysVArgumentFlow.Reference(7), arguments.register(8, 7))
        assertEquals(SysVArgumentFlow.Reference(6), arguments.register(8, 6))
        val flags = X64ControlFlow(
            X64Instructions(
                machineCode("31 c0 39 d6 f0 0f b0 4f 20 0f 94 c1 c3"),
                allowByteCompareExchange = true
            ).all()
        )
        val scalars = ScalarExpression(flags, arguments = SysVArgumentFlow(flags, allowByteCompareExchange = true))
        assertFails { scalars.before(12, Register(0, 1)) }
        assertFails { scalars.before(12, Register(1, 1)) }
    }

    @Test
    fun decodesBitScanWithoutPreservingItsDestinationOrOldComparison() {
        for ((prefix, width) in listOf("" to 4, "48 " to 8, "66 " to 2)) {
            val instruction = X64Instructions(machineCode("${prefix}0f bd d1")).decode(0)
            assertEquals(Operation.BIT_SCAN_REVERSE, instruction.operation)
            assertEquals(Register(2, width), instruction.destination)
            assertEquals(Register(1, width), instruction.source)
        }
        val extended = X64Instructions(machineCode("4d 0f bd 4c 24 10")).decode(0)
        assertEquals(Register(9, 8), extended.destination)
        assertEquals(Memory(12, null, 1, 16, 8), extended.source)
        for (code in listOf("f3 0f bd d1", "f0 0f bd d1", "0f bd", "66 66 0f bd d1"))
            assertFails { X64Instructions(machineCode(code)).all() }
        val pointers = X64ControlFlow(X64Instructions(machineCode("48 0f bd f9 48 8b 07 c3")).all())
        assertNull(SysVArgumentFlow(pointers).source(4))
        val scalar = X64ControlFlow(X64Instructions(machineCode("b8 09 00 00 00 0f bd c1 c3")).all())
        assertFails { ScalarExpression(scalar).before(8, Register(0, 4)) }
        val flags = X64ControlFlow(X64Instructions(machineCode("83 f8 00 0f bd d1 0f 44 c1 c3")).all())
        assertFails { ScalarExpression(flags).before(9, Register(0, 4)) }
    }

    @Test
    fun decodesLowQwordInterleaveWithoutTreatingItAsACopy() {
        val instructions = X64Instructions(machineCode("66 0f 14 c1 66 0f 14 45 80")).all()
        assertEquals(Operation.VECTOR_UNPACK_LOW_QWORDS, instructions[0].operation)
        assertEquals(Register(16, 16), instructions[0].destination)
        assertEquals(Register(17, 16), instructions[0].source)
        assertEquals(Memory(5, null, 1, -128, 16), instructions[1].source)
        for (code in listOf("0f 14 c1", "66 48 0f 14 c1", "f2 0f 14 c1"))
            assertFails { X64Instructions(machineCode(code)).all() }
    }

    @Test
    fun decodesScalarVectorTransfersAndDistinctPackedByteOperations() {
        val instructions = X64Instructions(
            machineCode(
                "66 0f 6e 40 10 66 0f 7e 40 20 66 48 0f 6e c1 66 0f 60 c1 f2 0f 70 c0 e1 66 0f 67 c0"
            )
        ).all()
        assertEquals(Register(16, 4), instructions[0].destination)
        assertEquals(Memory(0, null, 1, 16, 4), instructions[0].source)
        assertEquals(Memory(0, null, 1, 32, 4), instructions[1].destination)
        assertEquals(Register(16, 4), instructions[1].source)
        assertEquals(Register(16, 8), instructions[2].destination)
        assertEquals(Register(1, 8), instructions[2].source)
        assertEquals(Operation.VECTOR_UNPACK_LOW_BYTES, instructions[3].operation)
        assertEquals(Operation.VECTOR_SHUFFLE_LOW_WORDS, instructions[4].operation)
        assertEquals(0xe1, instructions[4].control)
        assertEquals(Operation.VECTOR_PACK_UNSIGNED_BYTES, instructions[5].operation)
        for (code in listOf("0f 6e c0", "48 0f 7e c0", "0f 60 c1", "66 48 0f 60 c1", "f2 48 0f 70 c0 00"))
            assertFails { X64Instructions(machineCode(code)).all() }
    }

    @Test
    fun keepsHighLaneLoadsDistinctFromWholeVectorCopies() {
        for (prefix in listOf("", "66 ")) {
            val instruction = X64Instructions(machineCode("${prefix}0f 16 48 10")).decode(0)
            assertEquals(Operation.VECTOR_HIGH_LOAD, instruction.operation)
            assertEquals(Register(17, 16), instruction.destination)
            assertEquals(Memory(0, null, 1, 16, 8), instruction.source)
        }
        val move = X64Instructions(machineCode("0f 16 c1")).decode(0)
        assertEquals(Operation.VECTOR_MOVE_LOW_TO_HIGH, move.operation)
        assertEquals(Register(16, 16), move.destination)
        assertEquals(Register(17, 16), move.source)
        for (code in listOf("48 0f 16 08", "66 0f 16 c1", "f3 0f 16 08", "0f 16"))
            assertFails { X64Instructions(machineCode(code)).all() }
    }

    @Test
    fun bitTestPreservesItsRegistersButInvalidatesComparisonFlags() {
        for ((prefix, width) in listOf("" to 4, "48 " to 8, "66 " to 2)) {
            val instruction = X64Instructions(machineCode("${prefix}0f a3 ca")).decode(0)
            assertEquals(Operation.BIT_TEST, instruction.operation)
            assertEquals(Register(2, width), instruction.destination)
            assertEquals(Register(1, width), instruction.source)
        }
        for (invalid in listOf("0f a3 0a", "f0 0f a3 ca", "0f ab ca", "0f a3")) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
        val pointer = X64ControlFlow(X64Instructions(machineCode("48 0f a3 cf 8b 07 c3")).all())
        assertEquals(SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7), 4), SysVArgumentFlow(pointer).source(4))
        val scalar = X64ControlFlow(X64Instructions(machineCode("b8 09 00 00 00 0f a3 c8 c3")).all())
        assertEquals(
            9,
            ScalarExpression.evaluate(ScalarExpression(scalar).before(8, Register(0, 4))) { error("No read") })
        val condition = X64ControlFlow(X64Instructions(machineCode("83 f8 00 0f a3 c8 0f 44 c1 c3")).all())
        assertFails { ScalarExpression(condition).before(9, Register(0, 4)) }
    }

    @Test
    fun keepsLockedMemoryDecrementsDistinctFromOrdinaryArithmetic() {
        val instructions = X64Instructions(machineCode("f0 ff 0d 08 00 00 00 f0 49 ff 48 08")).all()
        assertEquals(listOf(Operation.ATOMIC_DEC, Operation.ATOMIC_DEC), instructions.map { it.operation })
        assertEquals(Memory(null, null, 1, 8, 4, true), instructions[0].destination)
        assertEquals(Memory(8, null, 1, 8, 8), instructions[1].destination)
        val increment = X64Instructions(machineCode("f0 ff 07")).decode(0)
        assertEquals(Operation.ATOMIC_INC, increment.operation)
        assertEquals(Memory(7, null, 1, 0, 4), increment.destination)
        for (invalid in listOf(
            "f0 ff c8", "f0 ff c0", "f0 89 07", "f0 66 ff 0f", "f0 44 ff 0f",
            "f0 f0 ff 0f", "f0 ff 0d 00"
        )) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
        val flow = X64ControlFlow(X64Instructions(machineCode("48 89 fe f0 ff 0e 8b 46 04 c3")).all())
        assertEquals(SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, 4), 4), SysVArgumentFlow(flow).source(6))
        val guarded = X64ControlFlow(X64Instructions(machineCode("8b 07 f0 ff 0f 0f 45 c1 c3")).all())
        assertFails {
            ScalarExpression(guarded, mapOf(7 to 16)).before(8, Register(0, 4))
        }
    }

    @Test
    fun decodesPackedIntegerConversionAndByteArithmeticWithSeparateRegisterBanks() {
        val instructions = X64Instructions(machineCode("45 0f 5b c1 44 0f 5b 47 10 fe c8 41 fe c0")).all()
        assertEquals(
            listOf(
                Operation.VECTOR_INTS_TO_FLOATS, Operation.VECTOR_INTS_TO_FLOATS,
                Operation.DEC, Operation.INC
            ), instructions.map { it.operation })
        assertEquals(Register(24, 16), instructions[0].destination)
        assertEquals(Register(25, 16), instructions[0].source)
        assertEquals(Memory(7, null, 1, 16, 16), instructions[1].source)
        assertEquals(Register(0, 1), instructions[2].destination)
        assertEquals(Register(8, 1), instructions[3].destination)
        for (invalid in listOf("66 0f 5b c0", "48 0f 5b c0", "0f 5b", "66 fe c0", "44 fe c0", "fe d0", "fe")) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
    }

    @Test
    fun distinguishesScalarDoubleMultiplicationAndIntegerTruncation() {
        val decoded = X64Instructions(
            machineCode(
                "f2 45 0f 59 c1 f2 44 0f 59 47 08 " +
                        "f2 41 0f 2c c9 f2 4c 0f 2c 47 10"
            )
        ).all()
        assertEquals(
            listOf(
                Operation.DOUBLE_MULTIPLY, Operation.DOUBLE_MULTIPLY,
                Operation.TRUNCATE_DOUBLE, Operation.TRUNCATE_DOUBLE
            ), decoded.map { it.operation })
        assertEquals(Register(24, 8), decoded[0].destination)
        assertEquals(Register(25, 8), decoded[0].source)
        assertEquals(Memory(7, null, 1, 8, 8), decoded[1].source)
        assertEquals(Register(1, 4), decoded[2].destination)
        assertEquals(Register(25, 8), decoded[2].source)
        assertEquals(Register(8, 8), decoded[3].destination)
        assertEquals(Memory(7, null, 1, 16, 8), decoded[3].source)
        for (invalid in listOf("f2 48 0f 59 c0", "66 f2 0f 59 c0", "f2 0f 59", "f2 0f 2c", "66 f2 0f 2c c0")) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
        val flow = X64ControlFlow(X64Instructions(machineCode("f2 48 0f 2c f0 48 8b 46 08 c3")).all())
        assertEquals(null, SysVArgumentFlow(flow).source(5))
    }

    @Test
    fun requiresAnExplicitImplicitRegisterModelForWideMultiply() {
        val code = machineCode("48 f7 ea")
        assertFails { X64Instructions(code).all() }
        val instruction = X64Instructions(code, allowWideMultiply = true).decode(0)
        assertEquals(Operation.MULTIPLY_WIDE, instruction.operation)
        assertEquals(null, instruction.destination)
        assertEquals(Register(2, 8), instruction.source)
        val wideBody = X64Instructions(machineCode("48 f7 ea c3"), allowWideMultiply = true).all()
        assertFails { SysVLocalArgument(X64ControlFlow(wideBody)) }
        assertFails { X64JumpTables.resolve(wideBody, 0) { _, _ -> error("Unexpected table read") } }
        for (invalid in listOf("f7 ea", "66 f7 ea", "48 f7 e2", "4c f7 ea", "48 f7")) {
            assertFails { X64Instructions(machineCode(invalid), allowWideMultiply = true).all() }
        }
        val explicit = X64Instructions(machineCode("69 c9 e8 03 00 00 48 6b c1 fe f2 44 0f 5e 47 08")).all()
        assertEquals(Operation.MULTIPLY_IMMEDIATE, explicit[0].operation)
        assertEquals(Register(1, 4), explicit[0].destination)
        assertEquals(Register(1, 4), explicit[0].source)
        assertEquals(1000L, explicit[0].immediate)
        assertEquals(Register(0, 8), explicit[1].destination)
        assertEquals(-2L, explicit[1].immediate)
        assertEquals(Operation.DOUBLE_DIVIDE, explicit[2].operation)
        assertEquals(Register(24, 8), explicit[2].destination)
        assertEquals(Memory(7, null, 1, 8, 8), explicit[2].source)
    }

    @Test
    fun distinguishesGuiFloatingPointOperationsAndShuffleControl() {
        val code = machineCode(
            "f2 44 0f 58 47 08 66 45 0f 2e c1 f3 44 0f 2a 47 04 " +
                    "f3 45 0f 5b c1 45 0f 58 c1 45 0f 59 c1 45 0f c6 c1 39 4d 0f af c1"
        )
        val instructions = X64Instructions(code).all()
        assertEquals(
            listOf(
                Operation.DOUBLE_ADD, Operation.SCALAR_COMPARE, Operation.INT_TO_FLOAT,
                Operation.VECTOR_TRUNCATE_FLOATS, Operation.VECTOR_ADD_FLOATS, Operation.VECTOR_MULTIPLY_FLOATS,
                Operation.VECTOR_SHUFFLE_FLOATS, Operation.MULTIPLY
            ), instructions.map { it.operation })
        assertEquals(Register(24, 8), instructions[0].destination)
        assertEquals(Memory(7, null, 1, 8, 8), instructions[0].source)
        assertEquals(Register(25, 8), instructions[1].source)
        assertEquals(Register(24, 4), instructions[2].destination)
        assertEquals(Memory(7, null, 1, 4, 4), instructions[2].source)
        assertEquals(Register(25, 16), instructions[3].source)
        assertEquals(0x39, instructions[6].control)
        assertEquals(Register(8, 8), instructions[7].destination)
        assertEquals(Register(9, 8), instructions[7].source)
        for (invalid in listOf(
            "f2 48 0f 58 c0", "f2 0f 2e c0", "66 48 0f 2e c0", "66 0f 58 c0",
            "66 0f c6 c0 01", "f3 48 0f 5b c0", "0f c6 c0", "f3 0f 2a", "0f af"
        )) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
    }

    @Test
    fun decodesVariableShiftsAndUnaryOperationsWithoutLosingTheirOperands() {
        val decoded = X64Instructions(machineCode("41 d3 e2 48 d3 f8 66 d3 e8 49 f7 d3 f7 d8")).all()
        assertEquals(
            listOf(Operation.SHL, Operation.SAR, Operation.SHR, Operation.NOT, Operation.NEG),
            decoded.map { it.operation })
        assertEquals(Register(10, 4), decoded[0].destination)
        assertEquals(Register(1, 1), decoded[0].source)
        assertEquals(Register(0, 8), decoded[1].destination)
        assertEquals(Register(0, 2), decoded[2].destination)
        assertEquals(Register(11, 8), decoded[3].destination)
        for (code in listOf("d3 d0", "44 d3 e0", "f7 e0", "d3")) {
            assertFails { X64Instructions(machineCode(code)).all() }
        }
    }

    @Test
    fun decodesRotatesWithExactWidthCountAndMemoryOperands() {
        val decoded = X64Instructions(machineCode("d3 c0 48 d3 c8 66 c1 c0 13 d0 c8 c0 47 08 09")).all()
        assertEquals(
            listOf(Operation.ROL, Operation.ROR, Operation.ROL, Operation.ROR, Operation.ROL),
            decoded.map { it.operation })
        assertEquals(Register(0, 4), decoded[0].destination)
        assertEquals(Register(1, 1), decoded[0].source)
        assertEquals(Register(0, 8), decoded[1].destination)
        assertEquals(Register(0, 2), decoded[2].destination)
        assertEquals(Immediate(19), decoded[2].source)
        assertEquals(Register(0, 1), decoded[3].destination)
        assertEquals(Immediate(1), decoded[3].source)
        assertEquals(Memory(7, null, 1, 8, 1), decoded[4].destination)
        assertEquals(Immediate(9), decoded[4].source)
        for (invalid in listOf("d3 d0", "d3 d8", "44 d3 c0", "f0 d3 07", "c1 c0", "d3")) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
    }

    @Test
    fun decodesSignedByteAndWordExtensionsWithoutLosingWidths() {
        val decoded = X64Instructions(machineCode("0f bf c9 48 0f be 47 08 66 0f be c1 44 0f bf 47 02")).all()
        assertEquals(List(4) { Operation.MOVSX }, decoded.map { it.operation })
        assertEquals(Register(1, 4), decoded[0].destination)
        assertEquals(Register(1, 2), decoded[0].source)
        assertEquals(Register(0, 8), decoded[1].destination)
        assertEquals(Memory(7, null, 1, 8, 1), decoded[1].source)
        assertEquals(Register(0, 2), decoded[2].destination)
        assertEquals(Register(1, 1), decoded[2].source)
        assertEquals(Register(8, 4), decoded[3].destination)
        for (invalid in listOf("66 0f bf c0", "0f be c4", "0f bf", "f3 0f bf c0")) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
    }

    @Test
    fun decodesPackedInputOperationsWithTheirExactWidthsAndRegisterBank() {
        val decoded = X64Instructions(
            machineCode(
                "f3 44 0f 7e 06 66 44 0f d6 45 f0 " +
                        "66 41 0f 72 f0 10 66 41 0f 72 e0 10 66 45 0f 6f c8 66 45 0f 66 c8 " +
                        "66 45 0f 76 c8 66 45 0f fe c8 66 45 0f db c8 66 45 0f df c8 66 45 0f eb c8 66 45 0f ef c8"
            )
        ).all()
        assertEquals(Register(24, 8), decoded[0].destination)
        assertEquals(Memory(6, null, 1, 0, 8), decoded[0].source)
        assertEquals(Memory(5, null, 1, -16, 8), decoded[1].destination)
        assertEquals(Register(24, 8), decoded[1].source)
        assertEquals(Operation.VECTOR_SHIFT_LEFT_DWORDS, decoded[2].operation)
        assertEquals(Operation.VECTOR_SHIFT_RIGHT_DWORDS, decoded[3].operation)
        assertEquals(Register(24, 16), decoded[2].destination)
        assertEquals(Immediate(16), decoded[2].source)
        assertEquals(
            listOf(
                Operation.VECTOR_MOV, Operation.VECTOR_GREATER_DWORDS, Operation.VECTOR_EQUAL_DWORDS,
                Operation.VECTOR_ADD_DWORDS, Operation.VECTOR_AND, Operation.VECTOR_AND_NOT, Operation.VECTOR_OR,
                Operation.VECTOR_XOR
            ), decoded.drop(4).map { it.operation })
        assertTrue(decoded.drop(4).all { it.destination == Register(25, 16) && it.source == Register(24, 16) })
        val low = X64Instructions(machineCode("44 0f 13 45 f0")).decode(0)
        assertEquals(Operation.VECTOR_MOV, low.operation)
        assertEquals(Memory(5, null, 1, -16, 8), low.destination)
        assertEquals(Register(24, 8), low.source)
        for (code in listOf(
            "0f 72 f0 10", "66 44 0f 72 f0 10", "66 0f 72 30 10", "66 0f 72 d0 10",
            "f3 48 0f 7e c0", "66 48 0f d6 c0", "0f fe c0", "66 48 0f 6f c0", "f3 0f 7e", "0f 13 c0"
        )) {
            assertFails { X64Instructions(machineCode(code)).all() }
        }
    }

    @Test
    fun distinguishesSignedIntegerConversionFromScalarMoves() {
        val decoded = X64Instructions(machineCode("f2 0f 2a c0 f2 4d 0f 2a ca f2 0f 2a 47 08")).all()
        assertTrue(decoded.all { it.operation == Operation.INT_TO_DOUBLE })
        assertEquals(Register(16, 8), decoded[0].destination)
        assertEquals(Register(0, 4), decoded[0].source)
        assertEquals(Register(25, 8), decoded[1].destination)
        assertEquals(Register(10, 8), decoded[1].source)
        assertEquals(Memory(7, null, 1, 8, 4), decoded[2].source)
        assertFails { X64Instructions(machineCode("f2 0f 2a")).all() }
        assertFails { X64Instructions(machineCode("66 f2 0f 2a c0")).all() }
    }

    @Test
    fun decodesImmediateSubtractWithBorrowWithoutTreatingItAsOrdinarySubtraction() {
        val instruction = X64Instructions(machineCode("83 d9 ff")).decode(0)
        assertEquals(Operation.SBB, instruction.operation)
        assertEquals(Register(1, 4), instruction.destination)
        assertEquals(Immediate(-1), instruction.source)
    }

    @Test
    fun decodesImmediateAddWithCarryWithoutTreatingItAsOrdinaryAddition() {
        val instruction = X64Instructions(machineCode("48 83 d2 00")).decode(0)
        assertEquals(Operation.ADC, instruction.operation)
        assertEquals(Register(2, 8), instruction.destination)
        assertEquals(Immediate(0), instruction.source)
    }

    @Test
    fun decodesScalarDoubleMovesWithoutAliasingIntegerRegisters() {
        val decoded = X64Instructions(machineCode("f2 0f 10 00 f2 44 0f 11 47 08 f2 45 0f 10 ca")).all()
        assertEquals(Operation.SCALAR_MOV, decoded[0].operation)
        assertEquals(Register(16, 8), decoded[0].destination)
        assertEquals(Memory(0, null, 1, 0, 8), decoded[0].source)
        assertEquals(Register(24, 8), decoded[1].source)
        assertEquals(Memory(7, null, 1, 8, 8), decoded[1].destination)
        assertEquals(Register(25, 8), decoded[2].destination)
        assertEquals(Register(26, 8), decoded[2].source)
        val subtraction = X64Instructions(machineCode("f2 0f 5c c0")).all().single()
        assertEquals(Operation.DOUBLE_SUBTRACT, subtraction.operation)
        assertEquals(Register(16, 8), subtraction.destination)
        assertEquals(Register(16, 8), subtraction.source)
        for (code in listOf("66 f2 0f 10 00", "f2 48 0f 10 00", "f2 0f 5c", "f2 44 0f 11")) {
            assertFails { X64Instructions(machineCode(code)).all() }
        }
    }

    @Test
    fun preservesByteArithmeticAccumulatorWidthsAndConditionWrites() {
        val decoded =
            X64Instructions(machineCode("32 47 04 48 05 ff ff ff ff 3d 00 01 00 00 41 0f 95 c6 48 63 f0")).all()
        assertEquals(Operation.XOR, decoded[0].operation)
        assertEquals(Register(0, 1), decoded[0].destination)
        assertEquals(Memory(7, null, 1, 4, 1), decoded[0].source)
        assertEquals(Operation.ADD, decoded[1].operation)
        assertEquals(Register(0, 8), decoded[1].destination)
        assertEquals(Immediate(-1), decoded[1].source)
        assertEquals(Operation.CMP, decoded[2].operation)
        assertEquals(Register(0, 4), decoded[2].destination)
        assertEquals(Operation.SET, decoded[3].operation)
        assertEquals(Register(14, 1), decoded[3].destination)
        assertEquals(5, decoded[3].condition)
        assertEquals(Operation.MOVSX, decoded[4].operation)
        assertEquals(Register(6, 8), decoded[4].destination)
        assertEquals(Register(0, 4), decoded[4].source)
        for (invalid in listOf("0f 95 c4", "66 0f 95 c0", "44 0f 95 c0", "0f 95 c8", "63 c0", "48 05 01")) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
    }

    @Test
    fun decodesRegistersWidthsAndSignedAddressing() {
        val code = machineCode("4c 8b 54 8f f8 48 8d 05 10 00 00 00 48 83 ec 28 0f b6 47 18 66 8b 07")
        val decoded = X64Instructions(code).all()
        assertEquals(listOf(5, 7, 4, 4, 3), decoded.map { it.size })
        assertEquals(Register(10, 8), decoded[0].destination)
        assertEquals(Memory(7, 1, 4, -8, 8), decoded[0].source)
        assertEquals(Operation.LEA, decoded[1].operation)
        assertEquals(Memory(null, null, 1, 16, 8, relative = true), decoded[1].source)
        assertEquals(Operation.SUB, decoded[2].operation)
        assertEquals(Register(4, 8), decoded[2].destination)
        assertEquals(Immediate(40), decoded[2].source)
        assertEquals(Operation.MOVZX, decoded[3].operation)
        assertEquals(Register(0, 4), decoded[3].destination)
        assertEquals(Memory(7, null, 1, 24, 1), decoded[3].source)
        assertEquals(Register(0, 2), decoded[4].destination)
    }

    @Test
    fun preservesSibSpecialCasesAndExtendedRegisters() {
        val decoded = X64Instructions(machineCode("4b 8b 04 65 80 ff ff ff 49 8b 05 80 ff ff ff 41 54 41 5c")).all()
        assertEquals(Memory(null, 12, 2, -128, 8), decoded[0].source)
        // REX.B does not turn the ModRM RIP-relative special case into R13 addressing.
        assertEquals(Memory(null, null, 1, -128, 8, relative = true), decoded[1].source)
        assertEquals(Register(12, 8), decoded[2].destination)
        assertEquals(Operation.POP, decoded[3].operation)
    }

    @Test
    fun computesRelativeTargetsFromInstructionEnd() {
        val decoded = X64Instructions(machineCode("e8 fb ff ff ff 75 f9 0f 84 f3 ff ff ff ff 50 18")).all()
        assertEquals(Immediate(0), decoded[0].destination)
        assertEquals(Operation.CALL, decoded[0].operation)
        assertEquals(Immediate(0), decoded[1].destination)
        assertEquals(5, decoded[1].condition)
        assertEquals(Immediate(0), decoded[2].destination)
        assertEquals(4, decoded[2].condition)
        assertEquals(Memory(0, null, 1, 24, 8), decoded[3].destination)
    }

    @Test
    fun acceptsLongNopPaddingWithoutAcceptingPrefixesOnOtherOperations() {
        val padding = machineCode("66 66 66 66 66 66 2e 0f 1f 84 00 00 00 00 00")
        val instruction = X64Instructions(padding).decode(0)
        assertEquals(Operation.NOP, instruction.operation)
        assertEquals(15, instruction.size)
        for (code in listOf(
            "66 66 8b 07", "2e 8b 07", "66 66 0f b6 07", "2e 0f 84 00 00 00 00",
            "66 66 66 66 66 66 66 2e 0f 1f 84 00 00 00 00 00"
        )) {
            assertFails { X64Instructions(machineCode(code)).all() }
        }
    }

    @Test
    fun distinguishesIntegerIncrementWidthFromIndirectCallWidth() {
        val decoded = X64Instructions(machineCode("ff c6 48 ff c6 ff ce ff d6")).all()
        assertEquals(Operation.INC, decoded[0].operation)
        assertEquals(Register(6, 4), decoded[0].destination)
        assertEquals(Register(6, 8), decoded[1].destination)
        assertEquals(Operation.DEC, decoded[2].operation)
        assertEquals(Register(6, 8), decoded[3].destination)
        assertEquals(Operation.CALL, decoded[3].operation)
        val word = X64Instructions(machineCode("66 ff 47 12 66 ff 4f 12")).all()
        assertEquals(Operation.INC, word[0].operation)
        assertEquals(Memory(7, null, 1, 18, 2), word[0].destination)
        assertEquals(Operation.DEC, word[1].operation)
        assertFails { X64Instructions(machineCode("66 ff d6")).all() }
    }

    @Test
    fun decodesAtomicExchangeConditionalMovesAndImmediateTests() {
        val decoded =
            X64Instructions(machineCode("b0 01 86 47 18 a8 01 f6 47 18 01 48 f7 c0 ff ff ff ff 4c 0f 44 c3")).all()
        assertEquals(Operation.MOV, decoded[0].operation)
        assertEquals(Register(0, 1), decoded[0].destination)
        assertEquals(Immediate(1), decoded[0].source)
        assertEquals(Operation.XCHG, decoded[1].operation)
        assertEquals(Memory(7, null, 1, 24, 1), decoded[1].destination)
        assertEquals(Register(0, 1), decoded[1].source)
        assertEquals(Operation.TEST, decoded[2].operation)
        assertEquals(Register(0, 1), decoded[2].destination)
        assertEquals(Memory(7, null, 1, 24, 1), decoded[3].destination)
        assertEquals(Immediate(-1), decoded[4].source)
        assertEquals(Operation.CMOV, decoded[5].operation)
        assertEquals(Register(8, 8), decoded[5].destination)
        assertEquals(Register(3, 8), decoded[5].source)
        assertEquals(4, decoded[5].condition)
    }

    @Test
    fun keepsVectorRegistersSeparateFromIntegerRegisters() {
        val decoded = X64Instructions(machineCode("0f 57 c0 44 0f 11 47 20 66 45 0f 28 ca")).all()
        assertEquals(Operation.VECTOR_XOR, decoded[0].operation)
        assertEquals(Register(16, 16), decoded[0].destination)
        assertEquals(Register(16, 16), decoded[0].source)
        assertEquals(Operation.VECTOR_MOV, decoded[1].operation)
        assertEquals(Memory(7, null, 1, 32, 16), decoded[1].destination)
        assertEquals(Register(24, 16), decoded[1].source)
        assertEquals(Register(25, 16), decoded[2].destination)
        assertEquals(Register(26, 16), decoded[2].source)
        for (code in listOf("48 0f 57 c0", "f3 48 0f 10 00", "f6 e0", "b4 01", "86 e0", "0f 44")) {
            assertFails { X64Instructions(machineCode(code)).all() }
        }
    }

    @Test
    fun failsClosedForUnsupportedMalformedOrTruncatedInstructions() {
        for (code in listOf("67 8b 00", "f0 83 00 01", "40 40 8b 00", "0f 0b", "8d c0", "88 e0", "41 90", "ff f8")) {
            assertFails { X64Instructions(machineCode(code)).all() }
        }
        val complete = machineCode("4c 8b 94 8f 78 56 34 12")
        assertEquals(8, X64Instructions(complete).decode(0).size)
        for (length in 1 until complete.size) {
            assertFailsWith<IllegalArgumentException> { X64Instructions(complete.slice(0, length)).decode(0) }
        }
        assertFailsWith<IllegalArgumentException> { X64Instructions(machineCode("90 90")).all(1) }
    }
}
