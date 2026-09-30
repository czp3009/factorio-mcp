package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PlayerIndexTest {
    private val code = "55 48 89 e5 48 8b 47 10 0f b7 40 0c ff c0 f2 0f 2a c0 " +
            "48 8b 4e 08 48 8b 56 18 48 29 ca 48 83 fa 10 7e 06 f2 0f 11 01 5d c3 31 c0 5d c3"

    private fun analyze(bytes: String, member: Long = 16, size: Long = 32) =
        PlayerIndex.analyze(machineCode(bytes), 0x1000, 32, member, size, LuaStackLayout(8, 16, 0, 16), 24, 64)

    @Test
    fun derivesTheUnsignedFieldActuallyStoredAsTheOneBasedLuaIndex() {
        assertEquals(PlayerIndex(12, 2), analyze(code))
        assertEquals(PlayerIndex(12, 2), analyze(code.replace("48 83 fa 10", "b8 01 00 00 00 48 83 fa 10")))
        assertEquals(PlayerIndex(20, 1), analyze(code.replace("0f b7 40 0c", "0f b6 40 14")))
        assertEquals(PlayerIndex(12, 2), analyze(code.replace("48 8b 47 10", "48 8b 47 18"), member = 24))
    }

    @Test
    fun rejectsWrongObjectsBoundsConversionsAndCapacityPaths() {
        assertFails { analyze(code, member = 8) }
        assertFails { analyze(code, size = 13) }
        for (changed in listOf(
            code.replace("48 8b 47 10", "48 8b 46 10"),
            code.replace("ff c0", "ff c8"),
            code.replace("f2 0f 2a c0", "f2 0f 2a c1"),
            code.replace("f2 0f 11 01", "f2 0f 11 09"),
            code.replace("48 8b 56 18", "48 8b 56 20"),
            code.replace("48 29 ca", "48 29 c2"),
            code.replace("48 83 fa 10", "48 83 fa 08"),
            code.replace("48 83 fa 10", "ba 01 00 00 00 48 83 fa 10"),
            code.replace("7e 06", "7f 06"),
            code.replace("7e 06", "7e 01"),
        )) assertFails(changed) { analyze(changed) }
    }
}
