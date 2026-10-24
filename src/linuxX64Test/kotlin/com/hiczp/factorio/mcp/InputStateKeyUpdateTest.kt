package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InputStateKeyUpdateTest {
    @Test
    fun derivesKindsOriginalCodeAndRecordStoresAcrossInlinedSearches() {
        for (stride in listOf(32L, 64L)) {
            val fixture = KeySearchFixture(stride)
            val result = fixture.update()
            assertEquals(5L, result.press.kind)
            assertEquals(6L, result.release.kind)
            assertEquals(fixture.code, result.code)
            assertEquals(fixture.map, result.map)
            assertEquals(fixture.held, result.held)
            assertEquals(25L, result.requiredValueSize)
            val held = fixture.flow.body.getValue(result.press.stores.single().site)
            assertFails { fixture.update(fixture.replace(held.copy(source = Immediate(0)))) }
            assertFails {
                fixture.update(
                    fixture.replace(
                        held.copy(
                            destination = (held.destination as Memory).copy(displacement = 256)
                        )
                    )
                )
            }
            val call = fixture.flow.body.getValue(result.release.lookup)
            assertFails {
                fixture.update(
                    fixture.replace(call.copy(destination = Immediate(fixture.insertion + 1)))
                )
            }
        }
    }

    @Test
    fun derivesModifierCopyAndRejectsAnotherFieldOrReceiver() {
        val fixture = KeySearchFixture()
        val update = fixture.update()
        fun analyze(flow: X64ControlFlow = fixture.flow, field: Long = fixture.eventModifiers) =
            InputStateModifiers.analyze(flow, fixture.stateSize, fixture.header, update, field)
        assertEquals(fixture.stateModifiers, analyze())
        assertFails { analyze(field = fixture.eventModifiers + 1) }
        val store = fixture.flow.instructions.single { (it.destination as? Memory)?.width == 2 }
        assertFails {
            analyze(
                fixture.replace(
                    store.copy(destination = (store.destination as Memory).copy(base = 12))
                )
            )
        }
        assertFails { analyze(fixture.replace(store.copy(source = Register(2, 2)))) }
    }
}
