package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class ChatStringEntriesTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture_destroy", 0x2000, 1, 2, 1))

    @Test
    fun verifiesNativeConstructionAndExplicitByteRangeForwarding() {
        val construct = "48 8d 47 10 48 c7 47 08 00 00 00 00 48 89 07 c6 47 10 00 c3"
        ChatStringEntries.constructor(machineCode(construct), string)
        assertFails {
            ChatStringEntries.constructor(
                machineCode(construct.replace("c6 47 10 00", "c6 47 10 01")),
                string
            )
        }
        assertFails { ChatStringEntries.constructor(machineCode(construct), string.copy(data = 8)) }
        assertFails { ChatStringEntries.constructor(machineCode("48 8b 06 " + construct), string) }
        val assign = "49 89 d0 48 8b 57 08 48 89 f1 31 f6 e9 ef 00 00 00"
        ChatStringEntries.assignment(machineCode(assign), 0x1000, 0x1100, string)
        assertFails {
            ChatStringEntries.assignment(
                machineCode(
                    "48 8b 06 " + assign.replace("e9 ef", "e9 ec")
                ), 0x1000, 0x1100, string
            )
        }
        for (invalid in listOf(
            assign.replace("49 89 d0", "49 89 f0"),
            assign.replace("57 08", "57 10"),
            assign.replace("48 89 f1", "48 89 d1"),
            assign.replace("31 f6", "31 ff"),
            assign.replace("e9 ef", "e9 ee"),
        )) assertFails { ChatStringEntries.assignment(machineCode(invalid), 0x1000, 0x1100, string) }
    }
}
