package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class ChatTest {
    private fun record(id: Int, tick: Int = id, text: String = "message $id") =
        ChatRecord(id.toULong(), tick.toULong(), 0, 0, text, text)

    private fun snapshot(vararg records: ChatRecord, console: ULong = 1u) =
        ChatSnapshot(console, listOf(records.size.toULong(), 0u), records.toList())

    @Test
    fun incrementalReadsPreserveDuplicatesAndPaging() {
        val history = ChatHistory()
        val first = history.read(snapshot(record(1)), null, 1)
        val cursor = first.getValue("cursor").jsonPrimitive.content
        val next =
            history.read(
                snapshot(record(4, text = "same"), record(3, text = "same"), record(2), record(1)),
                cursor,
                2,
            )
        assertEquals(2, next.getValue("messages").jsonArray.size)
        assertTrue(next.getValue("has_more").jsonPrimitive.boolean)
        val final =
            history.read(
                snapshot(record(4, text = "same"), record(3, text = "same"), record(2), record(1)),
                next.getValue("cursor").jsonPrimitive.content,
                2,
            )
        assertEquals(1, final.getValue("messages").jsonArray.size)
        assertFalse(final.getValue("has_more").jsonPrimitive.boolean)
        val empty =
            history.read(
                snapshot(record(4, text = "same")),
                final.getValue("cursor").jsonPrimitive.content,
                2,
            )
        assertEquals(0, empty.getValue("messages").jsonArray.size)
    }

    @Test
    fun cachedDisplayRefreshDoesNotInventAnotherMessage() {
        val history = ChatHistory()
        val record = record(1).copy(text = "")
        val first = history.read(snapshot(record), null, 5)
        val updated =
            history.read(
                snapshot(record.copy(text = "translated")),
                first.getValue("cursor").jsonPrimitive.content,
                5,
            )
        assertEquals(0, updated.getValue("messages").jsonArray.size)
        assertEquals(
            "translated",
            history
                .read(snapshot(record.copy(text = "translated")), null, 5)
                .getValue("messages")
                .jsonArray
                .single()
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun consoleReplacementAndForeignCursorAreExplicit() {
        val history = ChatHistory()
        val cursor =
            history.read(snapshot(record(1)), null, 5).getValue("cursor").jsonPrimitive.content
        assertTrue(
            history
                .read(snapshot(record(2), console = 2u), cursor, 5)
                .getValue("history_lost")
                .jsonPrimitive
                .boolean
        )
        assertFailsWith<IllegalArgumentException> {
            ChatHistory().read(snapshot(record(1)), cursor, 5)
        }
    }

    @Test
    fun rejectsCommandsMultilineAndUtf8Overflow() {
        for (text in
        listOf("/c game.print('x')", "  /help", "a\nb", "a\u0000b", "界".repeat(1366), " ")) {
            assertFailsWith<IllegalArgumentException> {
                parseChatMessage(buildJsonObject { put("text", text) })
            }
        }
        assertEquals("你好", parseChatMessage(buildJsonObject { put("text", "你好") }))
    }
}
