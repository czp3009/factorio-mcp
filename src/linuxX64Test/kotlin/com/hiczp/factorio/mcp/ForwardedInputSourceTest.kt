package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ForwardedInputSourceTest {
    @Test
    fun followsDecodedReceiverAndSlotWhilePreservingTheCaller() {
        for (member in listOf(16, 40, 88)) {
            val displacement = member.toString(16).padStart(2, '0')
            val direct = "55 48 89 e5 48 8b 7f $displacement 48 8b 07 5d ff 60 18"
            val register = "48 8b 4f $displacement 48 8b 01 48 8b 40 18 48 89 cf ff e0"
            for (code in listOf(direct, register)) {
                assertEquals(member.toLong(), ForwardedInputSource.inspect(machineCode(code), 128, 3))
                assertFails { ForwardedInputSource.inspect(machineCode(code), member.toLong(), 3) }
                assertFails { ForwardedInputSource.inspect(machineCode(code), 128, 2) }
            }
            for (code in listOf(
                direct.replace("48 8b 7f", "48 8b 7e"),
                direct.replace("5d", "90"),
                direct.replace("48 8b 07", "48 8b 06"),
                direct.replace("48 8b 07", "48 89 07"),
                "e8 00 00 00 00 $direct",
                direct.replace("ff 60 18", "ff 50 18 c3"),
            )) assertFails { ForwardedInputSource.inspect(machineCode(code), 128, 3) }
        }
    }
}
