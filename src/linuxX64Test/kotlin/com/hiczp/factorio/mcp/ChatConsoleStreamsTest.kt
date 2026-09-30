package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatConsoleStreamsTest {
    private val code = "53 48 8d 5f 08 48 8d 47 20 80 79 0c 00 48 0f 45 d8 " +
            "48 8b 33 48 89 d7 e8 e4 00 00 00 5b c3"
    private val lists = ChatConsoleLists(
        listOf(
            ChatConsoleLists.ListFields(8, 8, 16, 24),
            ChatConsoleLists.ListFields(32, 32, 40, 48)
        )
    )

    private fun flow(code: String = this.code) = X64ControlFlow(X64Instructions(machineCode(code)).all())

    @Test
    fun connectsOriginalSettingsToBothListInsertions() {
        val flow = flow()
        val selection = ChatConsoleStreams.selection(flow, lists)
        assertEquals(ChatConsoleStreams.Selection(13, 12, 8, 32), selection)
        for (value in 0..1) ChatConsoleStreams.verifyInsertion(flow, selection, value, 256, 0)
        val reversed = flow(code.replace("0f 45", "0f 44"))
        val other = ChatConsoleStreams.selection(reversed, lists)
        assertEquals(ChatConsoleStreams.Selection(13, 12, 32, 8), other)
        for (value in 0..1) ChatConsoleStreams.verifyInsertion(reversed, other, value, 256, 0)
    }

    @Test
    fun rejectsUnrelatedSettingsListsAndInsertionPointers() {
        for (invalid in listOf(
            code.replace("80 79", "80 7a"), code.replace("0c 00", "0c 01"),
            code.replace("47 20", "47 28"), code.replace("0f 45", "0f 47")
        ))
            assertFails { ChatConsoleStreams.selection(flow(invalid), lists) }
        val selection = ChatConsoleStreams.selection(flow(), lists)
        assertFails {
            ChatConsoleStreams.verifyInsertion(
                flow(code.replace("48 8b 33", "48 89 de")),
                selection,
                0,
                256,
                0
            )
        }
        assertFails { ChatConsoleStreams.verifyInsertion(flow(), selection, 1, 256, 8) }
        assertFails { ChatConsoleStreams.verifyInsertion(flow(), selection, 0, 257, 0) }
    }
}
