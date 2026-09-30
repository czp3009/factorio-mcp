package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatPayloadTypeTest {
    private val code = "41 89 f7 e8 f8 00 00 00 41 8d 47 fb 0f b7 c0 " +
            "48 8d 0d ea 00 00 00 48 8b 04 c1 48 8b 78 08 48 8d 05 db 02 00 00 48 39 c7 74 01 c3 c3"

    private fun resolve(text: String = code, type: Long = 0x1200, name: Long = 0x1300): ChatPayloadType =
        ChatPayloadType.analyze(machineCode(text), 0x1000, 7, 0x1200, 0x1300) { location ->
            when (location) {
                0x1110L -> type
                0x1208L -> name
                else -> error("Unexpected fixture pointer read: $location")
            }
        }

    @Test
    fun resolvesOriginalScalarIndexAndComparedStringRtti() {
        assertEquals(ChatPayloadType(0x1110, 0x1200, 0x1300, 22), resolve())
    }

    @Test
    fun rejectsDifferentIndexRttiNameAndComparison() {
        assertFails { resolve(type = 0x1400) }
        assertFails { resolve(name = 0x1500) }
        for (invalid in listOf(
            code.replace("41 89 f7", "41 89 d7"),
            code.replace("47 fb", "47 fa"),
            code.replace("78 08", "78 10"),
            code.replace("05 db", "05 da"),
            code.replace("48 39 c7", "48 39 c6"),
            code.replace("74 01", "75 01"),
            code.replace("74 01", "74 00"),
        )) assertFails { resolve(invalid) }
    }
}
