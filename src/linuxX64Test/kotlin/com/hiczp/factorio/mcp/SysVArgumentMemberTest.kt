package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVArgumentMemberTest {
    @Test
    fun identifiesOriginalPointerArgumentsWithoutCallingTheSetter() {
        val code = "55 48 89 e5 48 89 fb 48 85 f6 74 04 48 89 73 20 c3"
        assertEquals(32L, SysVArgumentMember.analyze(machineCode(code), 0x1000, 64))
        for (changed in listOf(
            code.replace("48 89 73 20", "48 89 7b 20"),
            code.replace("48 89 73 20", "48 89 73 40"),
            code.replace("48 89 73 20", "48 89 73 21"),
            code.replace("48 89 73 20", "89 73 20 90"),
            code.replace("48 89 fb", "48 89 f3"),
        )) assertFails { SysVArgumentMember.analyze(machineCode(changed), 0x1000, 64) }
        assertFails { SysVArgumentMember.analyze(machineCode("e8 fb 00 00 00 48 89 77 20 c3"), 0x1000, 64) }
    }

    @Test
    fun acceptsOnlyPreservedOriginalPointersAfterConstructorCalls() {
        val code = "53 41 54 48 83 ec 08 48 89 fb 49 89 f4 e8 f2 00 00 00 4c 89 63 20 48 83 c4 08 41 5c 5b c3"
        assertEquals(32L, SysVArgumentMember.analyze(machineCode(code), 0x1000, 64))
        assertFails { SysVArgumentMember.analyze(machineCode(code.replace("49 89 f4", "49 89 fc")), 0x1000, 64) }
        assertFails { SysVArgumentMember.analyze(machineCode(code.replace("4c 89 63 20", "48 89 73 20")), 0x1000, 64) }
        assertFails { SysVArgumentMember.analyze(machineCode(code.replace("48 83 ec 08", "48 83 ec 10")), 0x1000, 64) }
    }
}
