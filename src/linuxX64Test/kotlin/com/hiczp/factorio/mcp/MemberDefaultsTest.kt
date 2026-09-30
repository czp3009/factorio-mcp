package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MemberDefaultsTest {
    private fun defaults(
        code: String,
        fields: List<InlineArgumentFields.Field> = listOf(InlineArgumentFields.Field(8, 4))
    ) =
        MemberDefaults.analyze(X64ControlFlow(X64Instructions(machineCode(code)).all()), 64, 0, fields)

    @Test
    fun selectsBytesOfCoalescedConstantsAndRequiresEveryReturnToAgree() {
        assertEquals(
            mapOf(2L to 0x66, 3L to 0x55, 4L to 0x44, 5L to 0x33),
            defaults("48 b8 88 77 66 55 44 33 22 11 48 89 07 c3", listOf(InlineArgumentFields.Field(2, 4)))
        )
        val assignment = "c7 47 08 07 00 00 00"
        val expected = mapOf(8L to 7, 9L to 0, 10L to 0, 11L to 0)
        assertEquals(expected, defaults("$assignment c3"))
        assertEquals(expected, defaults("85 f6 74 08 $assignment c3 $assignment c3"))
        assertFails { defaults("85 f6 74 08 $assignment c3 c7 47 08 09 00 00 00 c3") }
        assertFails { defaults("85 f6 74 07 $assignment c3") }
        assertFails { defaults("66 c7 47 08 07 00 c3") }
    }

    @Test
    fun forgetsDefaultsAcrossCallsAndPossibleAliasingWrites() {
        val assignment = "c7 47 08 07 00 00 00"
        assertFails { defaults("$assignment e8 00 01 00 00 c3") }
        assertFails { defaults("$assignment c7 06 00 00 00 00 c3") }
        assertFails { defaults("$assignment 89 77 08 c3") }
        assertFails { defaults("$assignment c6 47 09 01 c3", listOf(InlineArgumentFields.Field(63, 4))) }
        assertFails { defaults("$assignment e9 00 01 00 00") }
    }
}
