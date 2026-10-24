package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class PrivateFrameTest {
    private fun verify(middle: String) =
        PrivateFrame.verify(
            X64ControlFlow(
                X64Instructions(
                        machineCode(
                            "55 48 89 e5 48 83 ec 10 85 ff 74 06 48 8d 45 f0 eb 03 48 89 f0 " +
                                "$middle 48 83 c4 10 5d c3"
                        )
                    )
                    .all()
            )
        )

    @Test
    fun retainsPossibleAliasesUntilTheyAreCompletelyReplaced() {
        verify("31 c0 e8 00 01 00 00")
        verify("48 8b 00 e8 00 01 00 00")
        verify("e8 00 01 00 00")
        assertFails { verify("48 89 c7 e8 00 01 00 00") }
        assertFails { verify("48 89 06") }
        assertFails { verify("48 89 45 f8") }
        assertFails { verify("b0 00") }
        assertFails { verify("c6 00 00") }
    }
}
