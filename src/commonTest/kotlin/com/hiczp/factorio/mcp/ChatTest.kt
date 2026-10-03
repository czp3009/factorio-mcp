package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class ChatTest {
    private fun record(id: Int, tick: Int = id, text: String = "message $id") =
        ChatRecord(id.toULong(), tick.toULong(), 0, 0, text, text)

    private fun snapshot(vararg records: ChatRecord, console: ULong = 1u) =
        ChatSnapshot(console, listOf(records.size.toULong(), 0u), records.toList())

    private fun JsonObject.offset() = checkNotNull(parseChatRead(buildJsonObject {
        put("offset", getValue("next_offset"))
    }).offset)

    @Test
    fun incrementalReadsPreserveDuplicatesAndPaging() {
        val history = ChatHistory()
        val first = history.read(snapshot(record(1)), null, 1)
        val offset = first.offset()
        val next =
            history.read(
                snapshot(record(4, text = "same"), record(3, text = "same"), record(2), record(1)),
                offset,
                2,
            )
        assertEquals(2, next.getValue("messages").jsonArray.size)
        assertTrue(next.getValue("has_more").jsonPrimitive.boolean)
        val final =
            history.read(
                snapshot(record(4, text = "same"), record(3, text = "same"), record(2), record(1)),
                next.offset(),
                2,
            )
        assertEquals(1, final.getValue("messages").jsonArray.size)
        assertFalse(final.getValue("has_more").jsonPrimitive.boolean)
        val empty =
            history.read(
                snapshot(record(4, text = "same")),
                final.offset(),
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
                first.offset(),
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
    fun consoleReplacementIsExplicitAndOffsetsSurviveReaderRestarts() {
        val history = ChatHistory()
        val offset = history.read(snapshot(record(1)), null, 5).offset()
        assertTrue(
            history
                .read(snapshot(record(2), console = 2u), offset, 5)
                .getValue("history_lost")
                .jsonPrimitive
                .boolean
        )
        assertTrue(ChatHistory().read(snapshot(record(1).copy(identity = 99u)), offset, 5)
            .getValue("messages").jsonArray.isEmpty())
    }

    @Test
    fun offsetsDistinguishMessagesSharingTheSameTickAndText() {
        val history = ChatHistory()
        val snapshot = snapshot(record(2, 7, "same"), record(1, 7, "same"))
        val first = history.read(snapshot, null, 2)
        val rows = first.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)
        assertNotEquals(rows[0].getValue("offset"), rows[1].getValue("offset"))
        val next = history.read(snapshot, ChatOffset(7u, listOf(1, 0)), 2)
        assertEquals(listOf(rows[1]), next.getValue("messages").jsonArray.toList())
        assertEquals(rows[1].getValue("offset"), next.getValue("next_offset"))
    }

    @Test
    fun validatesPollingArgumentsAndRecentHistoryOffsets() {
        assertEquals(ChatReadRequest(null, 64, 0), parseChatRead(buildJsonObject {}))
        assertEquals(ChatReadRequest(null, 64, 0), parseChatRead(buildJsonObject { put("offset", 0) }))
        assertEquals(ChatReadRequest(ChatOffset(7u), 1, 30), parseChatRead(buildJsonObject {
            put("offset", 7)
            put("limit", 1)
            put("timeout", 30)
        }))
        for (args in listOf(
            "{\"offset\":-1}", "{\"offset\":\"0\"}", "{\"offset\":null}",
            "{\"offset\":{}}", "{\"offset\":{\"tick\":1,\"counts\":[1]}}",
            "{\"offset\":{\"tick\":1,\"counts\":[-1,0]}}", "{\"offset\":{\"tick\":1,\"counts\":[65537,0]}}",
            "{\"timeout\":-1}", "{\"timeout\":0.5}", "{\"timeout\":\"1\"}",
            "{\"limit\":0}", "{\"limit\":129}", "{\"after\":\"abc:7\"}",
        )) {
            assertFails { parseChatRead(Json.parseToJsonElement(args).jsonObject) }
        }
        assertEquals(ChatOffset(ULong.MAX_VALUE), parseChatRead(Json.parseToJsonElement(
            "{\"offset\":18446744073709551615}"
        ).jsonObject).offset)
    }

    @Test
    fun boundaryCountsPageAndObserveLateMessagesFromEachStorageAtTheSameTick() {
        val history = ChatHistory()
        val records = listOf(record(3, 7), record(2, 7), record(1, 7),
            record(5, 7).copy(stream = 1), record(4, 7).copy(stream = 1))
        val snapshot = ChatSnapshot(1u, listOf(3u, 2u), records)
        val first = history.read(snapshot, ChatOffset(7u), 2)
        assertEquals(ChatOffset(7u, listOf(2, 0)), first.offset())
        val second = history.read(snapshot, first.offset(), 2)
        assertEquals(ChatOffset(7u, listOf(3, 1)), second.offset())
        val third = history.read(snapshot, second.offset(), 2)
        assertEquals(ChatOffset(7u, listOf(3, 2)), third.offset())
        assertTrue(history.read(snapshot, third.offset(), 2).getValue("messages").jsonArray.isEmpty())
        // A later synchronized message at a paused world's unchanged tick precedes the local stream
        // in result order. Separate counts must not mistake it for a previously consumed local entry.
        val later = ChatSnapshot(1u, listOf(4u, 3u), listOf(record(6, 7)) + records.take(3) +
                listOf(record(7, 7).copy(stream = 1)) + records.drop(3))
        val page = history.read(later, third.offset(), 2)
        assertEquals(listOf("message 6", "message 7"), page.getValue("messages").jsonArray.map {
            it.jsonObject.getValue("text").jsonPrimitive.content
        })
        assertEquals(ChatOffset(7u, listOf(4, 3)), page.offset())
    }

    @Test
    fun pointerValuesAndReaderObservationStartDoNotAffectWatermarks() {
        val first = ChatHistory().read(snapshot(record(2, 7), record(1, 7)), null, 2)
        val other = ChatHistory().read(snapshot(record(2, 7).copy(identity = 199u),
            record(1, 7).copy(identity = 299u), console = 55u), null, 2)
        assertEquals(first, other)
    }

    @Test
    fun truncatedOrExpiredTickBoundariesAreExplicitAndFutureOffsetsDoNotRegress() {
        val history = ChatHistory()
        val partial = history.read(ChatSnapshot(1u, listOf(129u, 0u), listOf(record(2, 7), record(1, 7))),
            ChatOffset(7u), 2)
        assertTrue(partial.getValue("boundary_truncated").jsonPrimitive.boolean)
        assertTrue(partial.getValue("history_lost").jsonPrimitive.boolean)
        val expired = history.read(snapshot(), ChatOffset(7u, listOf(2, 0)), 2)
        assertTrue(expired.getValue("history_lost").jsonPrimitive.boolean)
        val future = ChatOffset(99u)
        assertEquals(future, history.read(snapshot(record(1, 7)), future, 2).offset())
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
