package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InputTaskOperationsTest {
    @Test
    fun mixedControlsPreserveChordOrderAndFiniteMotion() {
        val motion = listOf(InputMotion(2, InputPosition(30, 40)), InputMotion(5, InputPosition(50, 60)))
        val request = InputSequenceRequest(listOf(InputOperation(listOf(
            InputControl.Keyboard("W"),
            InputControl.Mouse("button_5", InputPosition(10, 20), "down", motion),
            InputControl.Keyboard("LSHIFT"),
        ), 5)), false)
        val operation = resolveInputTaskOperations(request) {
            assertEquals(listOf("W", "LSHIFT"), it)
            listOf(26u, 225u)
        }.single()
        assertEquals(5u, operation.ticks)
        assertEquals(listOf(InputTaskButton(0u, 26u), InputTaskButton(1u, 5u), InputTaskButton(0u, 225u)), operation.buttons)
        assertEquals(InputPosition(10, 20), operation.position)
        assertEquals(-1, operation.wheel)
        assertEquals(motion, operation.motion)
    }

    @Test
    fun allLogicalMouseButtonsKeepTheirPortableOrder() {
        val names = listOf("left", "right", "middle", "button_4", "button_5")
        val request = InputSequenceRequest(names.map { name ->
            InputOperation(listOf(InputControl.Mouse(name, null)), 1)
        }, false)
        val operations = resolveInputTaskOperations(request) { emptyList() }
        assertEquals((1u..5u).map { listOf(InputTaskButton(1u, it)) }, operations.map { it.buttons })
    }

    @Test
    fun malformedCandidatesFailBeforeAdmission() {
        fun request(vararg controls: InputControl) = InputSequenceRequest(listOf(InputOperation(controls.toList(), 5)), true)
        assertFails { resolveInputTaskOperations(request(InputControl.Keyboard("W"))) { emptyList() } }
        assertFails { resolveInputTaskOperations(request(InputControl.Keyboard("W"))) { listOf(0u) } }
        assertFails { resolveInputTaskOperations(request()) { listOf(1u) } }
        assertFails {
            resolveInputTaskOperations(request(InputControl.Keyboard("W"), InputControl.Keyboard("OTHER"))) {
                listOf(26u, 26u)
            }
        }
        assertFails {
            resolveInputTaskOperations(request(InputControl.Mouse("left", null), InputControl.Mouse("left", null))) {
                emptyList()
            }
        }
        assertFails {
            resolveInputTaskOperations(request(InputControl.Mouse(null, InputPosition(-1, 0)))) { emptyList() }
        }
        assertFails {
            resolveInputTaskOperations(request(InputControl.Mouse(null, InputPosition(1, 2)),
                InputControl.Mouse(null, InputPosition(3, 4)))) { emptyList() }
        }
        assertFails { resolveInputTaskOperations(request(InputControl.Mouse(null, null))) { emptyList() } }
        assertFails {
            resolveInputTaskOperations(request(InputControl.Mouse(null, null, "up"), InputControl.Mouse(null, null, "down"))) {
                emptyList()
            }
        }
        assertFails {
            resolveInputTaskOperations(request(InputControl.Mouse(null, null, motion = listOf(InputMotion(6, InputPosition(0, 0)))))) {
                emptyList()
            }
        }
    }
}
