package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class MouseClickEventTest {
    @Test
    fun requiresACompleteEventCopyWithExactlyOneChangedDiscriminator() {
        fun body(type: Int, value: Int) =
            X64Instructions(
                    machineCode(
                        "48 83 ec 28 0f 10 06 0f 10 4e 10 0f 11 04 24 0f 11 4c 24 10 " +
                            "c7 44 24 ${type.toString(16).padStart(2, '0')} ${value.toString(16).padStart(2, '0')} 00 00 00 " +
                            "48 8d 34 24 e8 00 10 00 00 48 83 c4 28 c3"
                    )
                )
                .all()
        fun analyze(body: List<Instruction>, extent: Int = 32): MouseClickEvent.Proof {
            val call = body.single { it.operation == Operation.CALL }
            return MouseClickEvent.analyze(
                X64ControlFlow(body),
                (call.destination as Immediate).value,
                extent,
            )
        }
        for (type in listOf(4, 16, 24)) {
            assertEquals(MouseClickEvent.Proof(type.toLong(), 9), analyze(body(type, 9)))
        }
        assertFails { analyze(body(16, 9), 40) }
        assertFails { analyze(body(32, 9)) }
        val body = body(16, 9)
        assertFails {
            analyze(
                body.map {
                    if (it.source is Memory && it.source.base == 6 && it.source.displacement == 16L)
                        it.copy(source = it.source.copy(displacement = 0))
                    else it
                }
            )
        }
        assertFails {
            analyze(
                body.map {
                    if (it.operation == Operation.LEA) it.copy(destination = Register(7, 8)) else it
                }
            )
        }
    }
}
