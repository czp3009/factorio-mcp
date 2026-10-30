package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SpriteNameAbiTest {
    private fun code(text: String) =
        BinaryView(text.split(' ').map { it.toInt(16).toByte() }.toByteArray())

    private fun windows(text: String, size: Long = 32) =
        SpriteNameAbi.verify(
            code(text),
            1,
            2,
            8,
            128,
            size,
            setOf(0, 1, 2, 8, 9, 10, 11) + (16..21),
        )

    @Test
    fun acceptsRegisterCopiesAndUnrelatedInstructions() {
        windows("48 8b 41 30 49 39 c0 48 c7 42 10 00 00 00 00 c6 02 00 c3")
        windows(
            "49 89 c9 49 89 d2 4d 89 c3 90 49 8b 41 38 49 39 c3 49 c7 42 10 00 00 00 00 41 c6 02 00 c3"
        )
    }

    @Test
    fun acceptsBreakpointPaddingWithoutUsingUnreachableEvidence() {
        val body = "48 8b 41 30 49 39 c0 48 c7 42 10 00 00 00 00 c6 02 00 c3"
        windows("$body cc cc")
        assertEquals(X64Instructions.Operation.TRAP, X64Instructions(code("cc")).decode(0).operation)
        assertFails { windows("cc $body") }
        for (prefix in listOf("66", "48", "f0", "f3")) {
            assertFails { X64Instructions(code("$prefix cc")).decode(0) }
        }
    }

    @Test
    fun rejectsWrongOutputPointerAndOutOfBoundsStorage() {
        assertFails { windows("48 8b 41 30 49 39 c0 48 c7 41 10 00 00 00 00 c6 01 00 c3") }
        assertFails { windows("48 8b 41 30 49 39 c0 48 c7 42 10 00 00 00 00 c6 02 00 c3", 16) }
        assertFails { windows("48 8b 41 30 48 39 c0 48 c7 42 10 00 00 00 00 c6 02 00 c3") }
    }

    @Test
    fun acceptsSysVHiddenOutputAndRejectsSwappedArguments() {
        val body = code("48 8b 46 30 48 39 c2 48 c7 47 08 00 00 00 00 c6 47 10 00 c3")
        val clobbers = setOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)
        SpriteNameAbi.verify(body, 6, 7, 2, 128, 32, clobbers)
        assertFails { SpriteNameAbi.verify(body, 7, 6, 2, 128, 32, clobbers) }
    }
}
