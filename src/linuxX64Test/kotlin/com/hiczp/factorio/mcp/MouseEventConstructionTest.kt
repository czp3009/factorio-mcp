@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseEventLayout
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseGestureLayout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MouseEventConstructionTest {
    @Test
    fun writesTheDerivedEnterFieldWithoutChangingTheOtherPhases() = memScoped<Unit> {
        val fields = MouseEventFields(80, 8, 12, 48, 16, 20, 24, 32, 40, 41, 42)
        val event = alloc<FmLinuxMouseEventLayout>()
        val gesture = alloc<FmLinuxMouseGestureLayout>()
        fields.writeTo(event)
        assertEquals(0U, event.hasPrevious)
        assertEquals(0U, event.previous)
        gesture.downType = 71U
        val enter = MouseEnterConstruction(
            MouseEventConstruction.Template(37, emptyList(), 64),
            1024, 120, 24, emptyList()
        )
        enter.writeTo(event, gesture)
        assertEquals(1U, event.hasPrevious)
        assertEquals(64U, event.previous)
        assertEquals(37U, gesture.enterType)
        assertEquals(71U, gesture.downType)
        assertEquals(120U, gesture.previousTarget)
        assertEquals(24U, gesture.widgetTargetable)
        event.size = 64U
        assertFails { enter.writeTo(event, gesture) }
        fields.writeTo(event)
        assertEquals(0U, event.hasPrevious)
        assertFails { enter.copy(guiPrevious = 1020).writeTo(event, gesture) }
    }

    @Test
    fun requiresCompleteKnownFieldsAndOnlyZeroDefaultsForOtherConstructedBytes() {
        val fields = MouseEventFields(80, 8, 12, 48, 16, 20, 24, 32, 40, 41, 42)
        val writes = listOf(
            8L to 8, 16L to 4, 20L to 2, 24L to 4, 32L to 8,
            40L to 1, 41L to 1, 42L to 1, 48L to 8, 64L to 8
        ).map { LocalAggregate.Field(it.first, it.second) }
        val proof = LocalAggregate.Proof(
            100, listOf(
                LocalAggregate.Constant(24, 4, 91),
                LocalAggregate.Constant(64, 8, 0)
            ), listOf(48), emptyList(), writes
        )
        assertEquals(
            MouseEventConstruction.Template(91, (64L..71L).toList()),
            MouseEventConstruction.crossCheck(fields, proof)
        )
        assertEquals(
            MouseEventConstruction.Template(91, emptyList(), 64),
            MouseEventConstruction.crossCheck(fields, proof.copy(constants = proof.constants.take(1)), previous = 64)
        )
        for (invalid in listOf(24L, 48L, 73L)) assertFails {
            MouseEventConstruction.crossCheck(fields, proof, previous = invalid)
        }
        for (write in writes.filter { it.offset != 64L }) {
            assertFails { MouseEventConstruction.crossCheck(fields, proof.copy(written = writes - write)) }
        }
        assertFails { MouseEventConstruction.crossCheck(fields, proof.copy(receiverFields = listOf(64))) }
        assertFails { MouseEventConstruction.crossCheck(fields, proof.copy(receiverFields = listOf(48, 64))) }
        assertFails { MouseEventConstruction.crossCheck(fields, proof.copy(constants = proof.constants.take(1))) }
        assertFails { MouseEventConstruction.crossCheck(fields, proof.copy(constants = proof.constants.drop(1))) }
        assertFails {
            MouseEventConstruction.crossCheck(
                fields,
                proof.copy(constants = proof.constants + proof.constants.first())
            )
        }
        assertFails {
            MouseEventConstruction.crossCheck(
                fields, proof.copy(
                    constants =
                        listOf(proof.constants.first(), LocalAggregate.Constant(64, 8, 1))
                )
            )
        }
        assertFails {
            MouseEventConstruction.crossCheck(
                fields,
                proof.copy(written = writes + LocalAggregate.Field(63, 1))
            )
        }
        assertFails {
            MouseEventConstruction.crossCheck(
                fields,
                proof.copy(written = writes + LocalAggregate.Field(80, 1))
            )
        }
        assertFails {
            MouseEventConstruction.crossCheck(
                fields,
                proof.copy(written = writes + LocalAggregate.Field(9, 1))
            )
        }
        assertFails {
            MouseEventConstruction.crossCheck(
                fields, proof.copy(
                    constants =
                        proof.constants + LocalAggregate.Constant(72, 8, 0)
                )
            )
        }
    }
}
