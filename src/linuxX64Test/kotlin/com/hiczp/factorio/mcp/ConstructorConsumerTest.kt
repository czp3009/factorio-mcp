package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class ConstructorConsumerTest {
    @Test
    fun observesOnlyTerminalCallInputsWithoutInventingBorrowEffects() {
        val code =
            machineCode(
                "55 48 89 e5 48 83 ec 10 48 89 7d f8 31 ff 48 8b 7d f8 48 8d 75 f0 e8 00 10 00 00 c3"
            )
        val flow = X64ControlFlow(X64Instructions(code).all())
        val call = flow.instructions.single { it.operation == Operation.CALL }.offset
        val selected = flow.reaching(call)
        assertFails { ConstructorValues(selected, emptyMap()) }
        val values = ConstructorValues(selected, emptyMap(), terminalConsumer = call)
        assertEquals(ConstructorValues.Argument(7), values.register(call, 7))
        assertFails { ConstructorValues(flow, emptyMap(), terminalConsumer = call) }
    }
}
