package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class ChatSubmitArgumentsTest {
    private val code = "0f b7 57 18 66 89 56 0e 0f b7 4e 0c 48 85 c9 74 02 ff e0 c3"
    private fun verify(bytes: String) = ChatSubmitArguments.analyze(
        machineCode(bytes), 64, PlayerIndex(24, 2),
        96, 12, 32, 32
    )

    @Test
    fun checksOriginalIndexAndActionAcrossReturnAndTail() = verify(code)

    @Test
    fun rejectsChangedPlayersFieldsPayloadWritesAndFrameEffects() {
        for (invalid in listOf(
            code.replace("57 18", "56 18"), code.replace("57 18", "57 1a"),
            code.replace("56 0e", "56 0c"), code.replace("56 0e", "56 20"),
            "53 " + code, "48 89 fb " + code, code.replace("ff e0", "89 fe"),
        )) assertFails { verify(invalid) }
    }
}
