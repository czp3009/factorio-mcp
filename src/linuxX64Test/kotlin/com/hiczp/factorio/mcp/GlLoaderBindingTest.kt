package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertFails

class GlLoaderBindingTest {
    private fun body(): List<Instruction> = buildList {
        fun instruction(
            operation: Operation,
            destination: Operand? = null,
            source: Operand? = null,
            condition: Int? = null
        ) {
            add(Instruction(size.toLong(), 1, operation, destination, source, condition))
        }

        fun global(address: Long) = Memory(null, null, 1, address - 0x1000 - size - 1, 8, true)
        instruction(Operation.MOV, Register(7, 8), global(0x4000))
        instruction(Operation.TEST, Register(7, 8), Register(7, 8))
        instruction(Operation.JCC, Immediate(12), condition = 4)
        instruction(Operation.MOV, Register(0, 8), Memory(7, null, 1, 8, 8))
        instruction(Operation.TEST, Register(0, 8), Register(0, 8))
        instruction(Operation.JCC, Immediate(12), condition = 4)
        instruction(Operation.CMP, Memory(7, null, 1, 16, 4), Immediate(0))
        instruction(Operation.JCC, Immediate(12), condition = 4)
        instruction(Operation.LEA, Register(6, 8), global(0x3000))
        instruction(Operation.CALL, Register(0, 8))
        instruction(Operation.MOV, Register(3, 8), Register(0, 8))
        instruction(Operation.JMP, Immediate(17))
        instruction(Operation.LEA, Register(7, 8), global(0x3500))
        instruction(Operation.XOR, Register(3, 4), Register(3, 4))
        instruction(Operation.XOR, Register(0, 4), Register(0, 4))
        instruction(Operation.CALL, Immediate(0x4000))
        instruction(Operation.JMP, Immediate(17))
        instruction(Operation.MOV, global(0x2000), Register(3, 8))
        instruction(Operation.RET)
    }

    private fun inspect(body: List<Instruction> = body(), name: String = "glReadPixels") = GlLoaderBinding.analyze(
        body, 0x1000, 0x4000, 64, 0x2000, 0x5000, "glReadPixels"
    ) {
        require(it == 0x3000L)
        name
    }

    @Test
    fun connectsLookupNameAndNullFailurePathsToStorage() {
        inspect()
        assertFails { inspect(name = "glOther") }
    }

    @Test
    fun rejectsAlternateEntryPartialStoresWrongReturnsAndUnclearedErrors() {
        val body = body()
        fun reject(index: Int, change: (Instruction) -> Instruction) {
            assertFails("Changed instruction $index must reject") {
                inspect(body.mapIndexed { position, instruction -> if (position == index) change(instruction) else instruction })
            }
        }
        reject(2) { it.copy(condition = 5) }
        reject(5) { it.copy(destination = Immediate(17)) }
        reject(3) { it.copy(source = Memory(7, null, 1, 64, 8)) }
        reject(8) { it.copy(destination = Register(7, 8)) }
        reject(9) { it.copy(destination = Register(1, 8)) }
        reject(10) { it.copy(source = Register(2, 8)) }
        reject(13) { it.copy(operation = Operation.MOV) }
        reject(15) { it.copy(destination = Immediate(0x4008)) }
        reject(17) { it.copy(source = Register(3, 4)) }
        assertFails { inspect(body + Instruction(19, 1, Operation.JMP, Immediate(14))) }
    }
}
