package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class MouseWheelFieldTest {
    @Test
    fun connectsTheEventDeltaToTheTypedVirtualWheelConsumer() {
        fun body(offset: Int) =
            X64Instructions(
                    machineCode(
                        "55 48 89 e5 8b 76 ${offset.toString(16).padStart(2, '0')} 48 8b 07 ff 50 18 5d c3"
                    )
                )
                .all()
        fun analyze(body: List<Instruction>, slot: Int = 3) =
            MouseWheelField.analyze(X64ControlFlow(body), slot, 64)
        for (offset in listOf(8, 20, 48)) assertEquals(offset.toLong(), analyze(body(offset)))
        assertFails { analyze(body(8), 4) }
        assertFails { analyze(body(64)) }
        assertFails {
            analyze(
                body(8).map {
                    if (it.destination == Register(6, 4)) it.copy(operation = Operation.ADD) else it
                }
            )
        }
        assertFails {
            analyze(
                body(8).map {
                    if (it.destination == Register(0, 8)) it.copy(source = Memory(2, null, 1, 0, 8))
                    else it
                }
            )
        }
    }
}
