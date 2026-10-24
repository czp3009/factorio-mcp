package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatConsoleStreamsTest {
    private val code =
        "53 48 8d 5f 08 48 8d 47 20 41 80 78 0c 00 48 0f 45 d8 " +
            "48 8b 33 48 89 d7 e8 e3 00 00 00 5b c3"
    private val lists =
        ChatConsoleLists(
            listOf(
                ChatConsoleLists.ListFields(8, 8, 16, 24),
                ChatConsoleLists.ListFields(32, 32, 40, 48),
            )
        )

    private fun flow(code: String = this.code) =
        X64ControlFlow(X64Instructions(machineCode(code)).all())

    @Test
    fun connectsOriginalSettingsToBothListInsertions() {
        val flow = flow()
        val selection = ChatConsoleStreams.selection(flow, lists)
        assertEquals(ChatConsoleStreams.Selection(14, 12, 8, 32), selection)
        for (value in 0..1) ChatConsoleStreams.verifyInsertion(flow, selection, value, 256, 0)
        val reversed = flow(code.replace("0f 45", "0f 44"))
        val other = ChatConsoleStreams.selection(reversed, lists)
        assertEquals(ChatConsoleStreams.Selection(14, 12, 32, 8), other)
        for (value in 0..1) ChatConsoleStreams.verifyInsertion(reversed, other, value, 256, 0)
    }

    @Test
    fun joinsSeparateLoadsOfTheSameMemberWithoutEquatingPointerValues() {
        val code =
            "53 48 83 ec 10 48 8d 5f 08 48 85 d2 74 09 " +
                "48 8b 03 48 89 04 24 eb 07 48 8b 0b 48 89 0c 24 " +
                "48 8b 34 24 e8 00 01 00 00 48 83 c4 10 5b c3"
        fun source(text: String, attribute: Boolean) =
            flow(text).let { flow ->
                val call = flow.instructions.single { it.operation == Operation.CALL }
                ConstructorValues(flow, emptyMap(), attributeMembers = attribute)
                    .register(call.offset, 6)
            }
        assertEquals(ConstructorValues.MemberSource(7, 8), source(code, true))
        assertEquals(null, source(code, false))
        assertEquals(null, source(code.replace("48 8b 0b", "48 89 d9"), true))
        assertEquals(null, source(code.replace("48 8b 03", "48 8b 07"), true))
    }

    @Test
    fun rejectsUnrelatedSettingsListsAndInsertionPointers() {
        for (invalid in
            listOf(
                code.replace("80 78", "80 79"),
                code.replace("0c 00", "0c 01"),
                code.replace("47 20", "47 28"),
                code.replace("0f 45", "0f 47"),
            )) assertFails { ChatConsoleStreams.selection(flow(invalid), lists) }
        val selection = ChatConsoleStreams.selection(flow(), lists)
        assertFails {
            ChatConsoleStreams.verifyInsertion(
                flow(code.replace("48 8b 33", "48 89 de")),
                selection,
                0,
                256,
                0,
            )
        }
        assertFails { ChatConsoleStreams.verifyInsertion(flow(), selection, 1, 256, 8) }
        assertFails { ChatConsoleStreams.verifyInsertion(flow(), selection, 0, 257, 0) }
    }

    @Test
    fun readsFreshStorageSelectorsInLocalAndIncomingSettings() {
        fun wrapper(offset: Long, value: Int) =
            listOf(
                Instruction(0, 1, Operation.PUSH, Register(5, 8)),
                Instruction(1, 1, Operation.MOV, Register(5, 8), Register(4, 8)),
                Instruction(2, 1, Operation.SUB, Register(4, 8), Immediate(64)),
                Instruction(3, 1, Operation.LEA, Register(8, 8), Memory(5, null, 1, offset, 8)),
                Instruction(
                    4,
                    1,
                    Operation.MOV,
                    Memory(8, null, 1, 8, 1),
                    Immediate(value.toLong()),
                ),
                Instruction(5, 1, Operation.CALL, Immediate(1000)),
                Instruction(6, 1, Operation.ADD, Register(4, 8), Immediate(64)),
                Instruction(7, 1, Operation.POP, Register(5, 8)),
                Instruction(8, 1, Operation.RET),
            )
        for (offset in listOf(-32L, 16L)) for (value in 0..1) {
            val body = wrapper(offset, value)
            assertEquals(value, ChatConsoleStreams.storageSelector(X64ControlFlow(body), 5, 8))
            assertFails { ChatConsoleStreams.storageSelector(X64ControlFlow(body), 5, 9) }
            for (replacement in
                listOf(
                    body[4].copy(source = Register(0, 1)),
                    body[4].copy(destination = Memory(7, null, 1, 8, 1)),
                    body[4].copy(operation = Operation.ADD),
                )) assertFails {
                ChatConsoleStreams.storageSelector(
                    X64ControlFlow(body.toMutableList().also { it[4] = replacement }),
                    5,
                    8,
                )
            }
        }
        assertFails { ChatConsoleStreams.storageSelector(X64ControlFlow(wrapper(-32, 2)), 5, 8) }
        assertFails { ChatConsoleStreams.storageSelector(X64ControlFlow(wrapper(8, 1)), 5, 8) }
    }
}
