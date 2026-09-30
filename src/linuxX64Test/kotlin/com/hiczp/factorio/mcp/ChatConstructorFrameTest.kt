package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class ChatConstructorFrameTest {
    private val code = "53 48 89 fb e8 f7 00 00 00 48 89 43 10 5b c3"

    private fun verify(text: String) = ChatConstructorFrame.analyze(machineCode(text), 0x1000, 64, 0x1100)

    @Test
    fun preservesSavedRegistersAcrossOwnedAllocationAndExternalStores() = verify(code)

    @Test
    fun requiresIndependentIndexedAccessEvidenceAndRejectsFrameAliases() {
        val indexed = "53 48 89 fb e8 f7 00 00 00 31 c9 48 89 04 08 5b c3"
        assertFails { verify(indexed) }
        ChatConstructorFrame.analyze(machineCode(indexed), 0x1000, 64, 0x1100, setOf(11))
        assertFails {
            ChatConstructorFrame.analyze(
                machineCode(indexed.replace("04 08", "04 0c")),
                0x1000, 64, 0x1100, setOf(11)
            )
        }
    }

    @Test
    fun scalarAddressesCannotTruncateKnownFramePointers() {
        verify("53 8d 46 01 48 89 fb e8 f4 00 00 00 48 89 43 10 5b c3")
        assertFails { verify("53 8d 44 24 00 48 89 fb e8 f3 00 00 00 48 89 43 10 5b c3") }
    }

    @Test
    fun rejectsFrameCorruptionMisalignedCallsAndUnverifiedAllocation() {
        for (invalid in listOf(
            code.replace("5b c3", "5d c3"),
            code.replace("5b c3", "90 c3"),
            code.replace("48 89 43 10", "48 89 04 24"),
            code.replace("e8 f7", "e8 f8"),
            "48 89 fb e8 f8 00 00 00 48 89 43 10 c3",
        )) assertFails { verify(invalid) }
    }
}
