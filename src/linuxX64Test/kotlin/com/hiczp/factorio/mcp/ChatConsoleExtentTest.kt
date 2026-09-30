package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatConsoleExtentTest {
    private val code = "53 48 89 fb 48 8b 7b 18 be 50 00 00 00 e8 ee 00 00 00 5b c3"
    private val lists = ChatConsoleLists(
        listOf(
            ChatConsoleLists.ListFields(8, 8, 16, 24),
            ChatConsoleLists.ListFields(32, 32, 40, 48)
        )
    )

    private fun verify(
        code: String = this.code, ranges: List<DwarfRanges.Range> = listOf(DwarfRanges.Range(13, 18)),
        member: Long = 24, playerSize: Long = 64, fields: ChatConsoleLists = lists
    ) =
        ChatConsoleExtent.analyze(
            X64ControlFlow(X64Instructions(machineCode(code)).all()),
            256, ranges, member, playerSize, fields
        )

    @Test
    fun derivesOwnedConsoleBoundFromSizedDeletion() {
        assertEquals(80, verify())
        assertEquals(96, verify(code.replace("be 50", "be 60")))
        assertEquals(80, verify(ranges = listOf(DwarfRanges.Range(0, 8))))
    }

    @Test
    fun rejectsWrongOwnerDeletionScopeAndOutOfBoundsLists() {
        for (invalid in listOf(
            code.replace("89 fb", "89 f3"), code.replace("7b 18", "7b 20"),
            code.replace("be 50", "be 30"), code.replace("ee 00", "ed 00")
        )) assertFails { verify(invalid) }
        assertFails { verify(ranges = listOf(DwarfRanges.Range(0, 7))) }
        assertFails { verify(playerSize = 24) }
        assertFails { verify(fields = ChatConsoleLists(lists.lists.map { it.copy(count = 80) })) }
    }
}
