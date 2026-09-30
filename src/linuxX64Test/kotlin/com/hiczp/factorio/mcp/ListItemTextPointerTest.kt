package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ListItemTextPointerTest {
    private val code = "48 63 c2 48 8b 4e 20 48 c1 e0 04 48 8b 3c 01 48 8b 07 ff 50 18 c3"
    private fun verify(code: String) = ListItemTextPointer.analyze(machineCode(code), 64, 32, 16, 3)

    @Test
    fun connectsIndexedMemberToTypedTextDispatch() {
        assertEquals(0, verify(code))
        assertEquals(8, verify(code.replace("48 8b 3c 01", "48 8b 7c 01 08")))
        assertEquals(0, verify("41 89 d0 " + code.replace("48 63 c2", "49 63 c0")))
    }

    @Test
    fun rejectsWrongStorageIndexStrideReceiverAndSlot() {
        for (invalid in listOf(
            code.replace("63 c2", "63 c1"), code.replace("4e 20", "4f 20"),
            code.replace("4e 20", "4e 28"), code.replace("e0 04", "e0 03"),
            code.replace("8b 3c 01", "8b 3c 11"), code.replace("8b 07", "8b 06"),
            code.replace("50 18", "50 20"), code.replace("48 8b 3c 01", "48 8b 7c 01 10")
        )) {
            assertFails { verify(invalid) }
        }
    }
}
