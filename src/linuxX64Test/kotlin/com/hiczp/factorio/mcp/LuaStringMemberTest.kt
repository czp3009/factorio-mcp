package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaStringMemberTest {
    @Test
    fun connectsBothNativeStringPartsToOneBoundedOriginalObject() {
        val code =
            "55 48 89 e5 48 89 f0 48 8b 4f 20 48 8b 71 08 48 8b 51 10 " +
                "48 89 c7 e8 e5 00 00 00 b8 01 00 00 00 5d c3"
        val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("destroy", 0, 1, 2, 1))
        fun analyze(text: String, extent: Long = 64) =
            LuaStringMember.analyze(
                X64ControlFlow(X64Instructions(machineCode(text)).all()),
                256,
                48,
                extent,
                string,
            )
        assertEquals(8L, analyze(code))
        assertEquals(16L, analyze(code.replace("71 08", "71 10").replace("51 10", "51 18")))
        assertFails { analyze(code, 39) }
        for (invalid in
            listOf(
                code.replace("4f 20", "4f 30"),
                code.replace("4f 20", "4e 20"),
                code.replace("48 89 c7", "48 89 cf"),
                code.replace("51 10", "51 18"),
                code.replace("48 8b 51", "90 8b 51"),
                code.replace("71 08", "73 08"),
            )) assertFails { analyze(invalid) }
    }
}
