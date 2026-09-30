package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatRecordTickTest {
    private val code = "53 48 89 fb bf 60 00 00 00 e8 f2 00 00 00 48 8b 4b 08 48 8b 49 18 " +
            "48 8b 51 20 48 89 50 10 48 89 c7 e8 da 01 00 00 5b c3"

    private fun verify(code: String = this.code, player: Long = 48) = ChatRecordTick.analyze(
        X64ControlFlow(X64Instructions(machineCode(code)).all()), 256, NativeListNodeLayout(0, 0, 16, 96),
        64, 24, 64, 8, 32, player, 2, listOf(DwarfRanges.Range(26, 30)), listOf(DwarfRanges.Range(22, 30))
    )

    @Test
    fun derivesRecordTickAndRequiredConsoleBackPointer() {
        assertEquals(ChatRecordTick(0, 8, 24, 32), verify())
        assertEquals(ChatRecordTick(0, 16, 24, 32), verify(code.replace("4b 08", "4b 10")))
    }

    @Test
    fun rejectsWrongOwnerSourceAllocationAndOverlappingFields() {
        for (invalid in listOf(
            code.replace("89 fb", "89 f3"), code.replace("49 18", "49 20"),
            code.replace("51 20", "51 40"), code.replace("4b 08", "4b 40"), code.replace("bf 60", "bf 58"),
            code.replace("50 10", "50 18"), code.replace("50 10", "57 10"),
            code.replace("48 8b 51", "90 8b 51")
        )) assertFails { verify(invalid) }
        assertFails { verify(player = 0) }
    }
}
