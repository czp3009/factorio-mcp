package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatRecordPlayerTest {
    private val code = "53 41 54 48 83 ec 08 49 89 d4 bf 60 00 00 00 e8 ec 00 00 00 " +
            "48 89 c3 4d 85 e4 74 08 41 0f b7 44 24 18 eb 04 66 b8 ff ff 66 89 43 40 " +
            "48 83 c4 08 41 5c 5b c3"

    private fun verify(code: String) = ChatRecordPlayer.analyze(
        machineCode(code), 0x10000, 0x10100,
        NativeListNodeLayout(0, 0, 16, 96), 8, 32, PlayerIndex(24, 2)
    )

    @Test
    fun derivesIndexAndUnmodifiedNativeAbsentValue() {
        assertEquals(ChatRecordPlayer(48, 65535), verify(code))
        assertEquals(ChatRecordPlayer(48, 1234), verify(code.replace("b8 ff ff", "b8 d2 04")))
        assertEquals(ChatRecordPlayer(48, 65535), verify(code.replace("48 89 c3", "48 8d 18")))
    }

    @Test
    fun tracksSavedPlayerAcrossDisjointVectorLocals() {
        val saved = "53 48 83 ec 20 48 89 54 24 18 0f 11 44 24 00 bf 60 00 00 00 e8 e7 00 00 00 " +
                "48 89 c3 48 8b 54 24 18 48 85 d2 74 06 0f b7 42 18 eb 04 66 b8 ff ff 66 89 43 40 " +
                "48 83 c4 20 5b c3"
        assertEquals(ChatRecordPlayer(48, 65535), verify(saved))
        assertFails { verify(saved.replace("0f 11 44 24 00", "0f 11 44 24 10")) }
        assertFails { verify(saved.replace("48 89 54 24 18", "48 89 74 24 18")) }
        assertFails { verify(saved.replace("48 8b 54 24 18", "48 8b 54 24 10")) }
    }

    @Test
    fun rejectsWrongAllocationPlayerGuardAndField() {
        for (invalid in listOf(
            code.replace("bf 60", "bf 58"), code.replace("89 d4", "89 f4"),
            code.replace("74 08", "75 08"), code.replace("24 18", "24 20"),
            code.replace("43 40", "43 20"), code.replace("43 40", "47 40"),
            code.replace("eb 04", "eb 00"), code.replace("66 b8", "66 b9"),
            code.replace("48 89 c3", "48 8d 58 08"),
        )) assertFails { verify(invalid) }
    }
}
