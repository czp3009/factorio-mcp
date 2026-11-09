package com.hiczp.factorio.mcp

import kotlin.test.*

class ImagePointerMemberTest {
    private fun member(code: String, size: Long = 128, slot: Int = 1): Long {
        val bytes = machineCode(code)
        return ImagePointerMember.analyze(X64ControlFlow(X64Instructions(bytes).all()), bytes, 0, size, slot)
    }

    @Test
    fun associatesResetAndVirtualDeletionThroughTheSameStoredImage() {
        for (offset in listOf(24, 48, 88)) {
            val field = offset.toString(16).padStart(2, '0')
            val direct = "48 83 ec 08 48 8b 47 $field 48 c7 47 $field 00 00 00 00 48 8b 08 48 89 c7 ff 51 08 48 83 c4 08 c3"
            val register = "55 48 89 e5 48 8b 57 $field 48 c7 47 $field 00 00 00 00 48 89 d7 48 8b 02 48 8b 40 08 ff d0 5d c3"
            for (code in listOf(direct, register)) {
                assertEquals(offset.toLong(), member(code))
                assertFails { member(code, size = offset.toLong()) }
                assertFails { member(code, slot = 2) }
            }
            assertFails { member(direct.replace("48 c7 47 $field", "48 c7 47 10")) }
            assertFails { member(direct.replace("48 8b 47", "48 8b 46")) }
            assertFails { member(direct.replace("48 89 c7", "48 89 d7")) }
            assertFails { member(direct.replace("48 8b 08", "48 8b 0e")) }
            assertFails { member(direct.replace("48 c7 47", "c7 47")) }
        }
    }
}
