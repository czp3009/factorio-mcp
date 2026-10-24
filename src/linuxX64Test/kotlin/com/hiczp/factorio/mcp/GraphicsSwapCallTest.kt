package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class GraphicsSwapCallTest {
    private fun code() =
        listOf(
            Instruction(0, 1, Operation.MOV, Register(7, 8), Memory(7, null, 1, 32, 8)),
            Instruction(1, 1, Operation.MOV, Register(0, 8), Memory(7, null, 1, 0, 8)),
            Instruction(2, 1, Operation.CALL, Memory(0, null, 1, 24, 8)),
            Instruction(3, 1, Operation.MOV, Register(12, 8), Register(0, 8)),
            Instruction(4, 1, Operation.NOP),
            Instruction(5, 1, Operation.MOV, Register(7, 8), Register(12, 8)),
            Instruction(6, 1, Operation.CALL, Immediate(0x1000)),
            Instruction(7, 1, Operation.RET),
        )

    private fun inspect(body: List<Instruction>) =
        GraphicsSwapCall.windowMember(X64ControlFlow(body), 6, 3, 64)

    @Test
    fun connectsNativeWindowGetterAndTypedMemberAcrossCopies() {
        assertEquals(32, inspect(code()))
        assertFails {
            inspect(
                code().map {
                    if (it.offset == 2L) it.copy(destination = Memory(0, null, 1, 32, 8)) else it
                }
            )
        }
        assertFails {
            inspect(
                code().map {
                    if (it.offset == 1L) it.copy(source = Memory(6, null, 1, 0, 8)) else it
                }
            )
        }
        assertFails {
            inspect(code().map { if (it.offset == 3L) it.copy(source = Register(2, 8)) else it })
        }
        assertFails {
            inspect(
                code().map {
                    if (it.offset == 0L) it.copy(source = Memory(7, null, 1, 64, 8)) else it
                }
            )
        }
    }
}
