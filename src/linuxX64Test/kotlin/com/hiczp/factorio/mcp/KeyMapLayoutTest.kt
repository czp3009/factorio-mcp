package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class KeyMapLayoutTest {
    @Test
    fun derivesExistingKeyLocationWithoutCallingAnAllocatingHelper() {
        for (stride in listOf(32L, 64L)) for (map in listOf(0L, 16L)) {
            val fixture = KeySearchFixture(stride, map)
            val update = fixture.update()
            for (case in listOf(update.press, update.release)) {
                fun analyze(flow: X64ControlFlow = fixture.flow) =
                    KeyMapLayout.analyze(flow, fixture.stateSize, update, case)
                assertEquals(
                    KeyMapLayout(fixture.begin, fixture.end, stride, fixture.key),
                    analyze(),
                )
                val step =
                    fixture.flow.instructions.first {
                        it.offset >= case.entry &&
                            it.operation == Operation.ADD &&
                            it.destination == Register(0, 8)
                    }
                assertFails { analyze(fixture.replace(step.copy(source = Immediate(stride / 2)))) }
                val comparison =
                    fixture.flow.instructions.first {
                        it.offset >= case.entry &&
                            it.operation == Operation.CMP &&
                            it.destination is Memory
                    }
                assertFails {
                    analyze(
                        fixture.replace(
                            comparison.copy(
                                destination = (comparison.destination as Memory).copy(width = 2)
                            )
                        )
                    )
                }
                assertFails {
                    analyze(
                        fixture.replace(
                            comparison.copy(
                                operation = Operation.CALL,
                                destination = Immediate(1001),
                            )
                        )
                    )
                }
            }
        }
    }
}
