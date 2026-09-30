package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatConsoleListsTest {
    private val node = NativeListNodeLayout(0, 0, 16, 160)
    private val code = "48 8b 47 18 48 8b 48 28 " +
            "48 8d 51 18 48 89 51 20 48 89 51 18 48 c7 41 28 00 00 00 00 " +
            "48 8d 51 30 48 89 51 38 48 89 51 30 48 c7 41 40 00 00 00 00 c3"

    private fun analyze(code: String) =
        ChatConsoleLists.analyze(X64ControlFlow(X64Instructions(machineCode(code)).all()), 24, 40, node)

    @Test
    fun associatesEachCountResetWithItsOwnSentinelLinks() {
        assertEquals(
            listOf(ChatConsoleLists.ListFields(24, 24, 32, 40), ChatConsoleLists.ListFields(48, 48, 56, 64)),
            analyze(code).lists
        )
    }

    @Test
    fun rejectsWrongOwnersSentinelsCountsAndAliasing() {
        for (invalid in listOf(
            code.replace("47 18", "47 20"), code.replace("48 28", "48 30"),
            code.replace("51 20", "51 18"), code.replace("41 28", "41 20"),
            code.replace("51 30", "51 18"), code.replace("41 40", "41 28"),
            code.replace("48 89 51 20", "40 89 51 20"),
        )) assertFails { analyze(invalid) }
    }
}
