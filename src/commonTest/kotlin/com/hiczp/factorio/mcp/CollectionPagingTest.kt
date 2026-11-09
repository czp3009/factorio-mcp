package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.io.Buffer
import kotlinx.io.readIntLe
import kotlinx.serialization.json.*

class CollectionPagingTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun optionTextCanBeSlicedWithoutReturningTheWholeOptionLabel() {
        val request = parseUiCollection(json("""{"source":"options","path":[{"index":2}],"offset":3,"limit":4}"""))
        assertEquals(2, request.optionOffset)
        assertEquals(1, request.optionLimit)
        val snapshot = GameSnapshot("in_game", true, 1, listOf(
            WidgetSnapshot(0, "class Dropdown", "", true, 0, 0, 1, 1, false, selected = true,
                properties = WidgetProperties(options = WidgetOptions(listOf(WidgetOption("🚀")), 5, 2))),
        ), textRange = ObservationTextRange(3, 13, 7, true))
        val observation = snapshot.uiCollectionJson(request)
        assertEquals("Dropdown", observation.getValue("native_type").jsonPrimitive.content)
        val first = observation.getValue("collection").jsonObject
        assertEquals("🚀", first.getValue("value").jsonPrimitive.content)
        assertEquals(7, first.getValue("next_offset").jsonPrimitive.int)
        assertTrue(first.getValue("has_more").jsonPrimitive.boolean)
        val last = snapshot.copy(nodes = listOf(snapshot.nodes.single().copy(properties =
            WidgetProperties(options = WidgetOptions(listOf(WidgetOption("正文")), 5, 2)))),
            textRange = ObservationTextRange(7, 13, 13, false))
            .uiCollectionJson(request.copy(offset = 7, limit = 64)).getValue("collection").jsonObject
        assertEquals("正文", last.getValue("value").jsonPrimitive.content)
        assertFalse(last.getValue("has_more").jsonPrimitive.boolean)
        assertFalse(last.getValue("truncated").jsonPrimitive.boolean)
        for (path in listOf("""[{"index":-1}]""", """[{"index":0.5}]""", """[{"index":1},{"index":2}]""")) {
            assertFails { parseUiCollection(json("""{"source":"options","path":$path}""")) }
        }
    }

    @Test
    fun numericTablePathsPreserveZeroNegativeFractionalAndUnsignedIndices() {
        for (index in listOf("0", "-2", "0.5", "4294967295")) {
            val collection = parseUiCollection(json("""{"source":"gui","path":[{"property":"tags"},{"index":$index}]}"""))
            assertEquals(index, collection.path.last().jsonObject.getValue("index").jsonPrimitive.content)
            val inspection = parseWorldQuery(json("""{"selection":{"kind":"inspect","target":{"kind":"game"},"path":[{"property":"players"},{"index":$index}]}}"""))
            assertEquals(index, inspection.arguments.getValue("inspection").jsonObject.getValue("path").jsonArray.last().jsonObject.getValue("index").jsonPrimitive.content)
        }
    }

    @Test
    fun observedPostorderRelationshipsProduceAnExactSiblingSelector() {
        val snapshot = json("""{"truncated":false,"nodes":[
            {"id":0,"parent":3,"type":"Button"},{"id":1,"parent":3,"type":"Label"},
            {"id":2,"parent":3,"type":"Button"},{"id":3,"type":"Window"}
        ]}""")
        val steps = parseSelector(uiSelectorForNode(snapshot, 2))
        assertEquals(listOf("Window", "Button"), steps.map { it.type })
        assertEquals(listOf(1, 2), steps.map { it.position })
        assertTrue(steps.all { it.child })
        assertFailsWith<IllegalArgumentException> {
            uiSelectorForNode(JsonObject(snapshot + ("truncated" to JsonPrimitive(true))), 2)
        }
    }

    @Test
    fun nativeTextRangesPreserveReportedOffsetsAndRejectInconsistentCopies() {
        val page = ObservationTextRange(1, 12, 8, true).json("界🚀")
        assertEquals("界🚀", page.getValue("value").jsonPrimitive.content)
        assertEquals(12, page.getValue("total").jsonPrimitive.int)
        assertEquals(8, page.getValue("next_offset").jsonPrimitive.int)
        assertTrue(page.getValue("truncated").jsonPrimitive.boolean)
        assertFailsWith<IllegalArgumentException> { ObservationTextRange(1, 12, 8, true).json("A") }
        assertFailsWith<IllegalArgumentException> { ObservationTextRange(1, 12, 8, false) }
        assertEquals("", ObservationTextRange(Int.MAX_VALUE, 12, 12, false).json("").getValue("value").jsonPrimitive.content)
    }

    @Test
    fun collectionPathsAdmitOnlyPassivePropertyAndIndexTraversal() {
        val value = parseUiCollection(json("""{"source":"gui","path":[{"property":"tags"},{"index":"opaque"},{"index":1}],"offset":32,"limit":90000}"""))
        assertEquals(3, value.path.size)
        assertEquals(32, value.offset)
        assertEquals(90000, value.limit)
        for (invalid in listOf(
            """{"source":"options","path":[{"property":"label"}]}""",
            """{"source":"gui","path":[{"method":"get_something"}]}""",
            """{"source":"gui","path":[{"index":true}]}""",
            """{"source":"gui","path":[{"property":"tags","index":1}]}""",
            """{"source":"text","offset":-1}""",
            """{"source":"text","limit":0}""",
        )) assertFailsWith<IllegalArgumentException>(invalid) { parseUiCollection(json(invalid)) }
    }

    @Test
    fun spatialQueriesHaveNoContinuationWhileGridPagesUseCoordinates() {
        val area = """{"left_top":{"x":-10,"y":-3},"right_bottom":{"x":8,"y":5}}"""
        val tiles = parseWorldQuery(json("""{"selection":{"kind":"tiles","area":$area},"limit":9}"""))
        assertEquals(9, tiles.arguments.getValue("limit").intArgument())
        val grid = parseWorldOverview(json("""{"area":$area,"cell_size":2,"start_cell":{"row":1,"column":7},"cell_limit":3}"""))
        assertEquals(3, grid.arguments.getValue("cell_limit").jsonPrimitive.int)
        for (invalid in listOf(
            """{"selection":{"kind":"player"},"start_position":{"x":1,"y":1}}""",
            """{"selection":{"kind":"tiles","area":$area},"start_position":{"x":-2,"y":1}}""",
            """{"selection":{"kind":"tiles","area":$area},"start_position":{"x":0.5,"y":1}}""",
        )) assertFailsWith<IllegalArgumentException> { parseWorldQuery(json(invalid)) }
        for (invalid in listOf(
            """{"start_cell":{"row":0,"column":0}}""",
            """{"area":$area,"cell_size":2,"start_cell":{"row":-1,"column":0}}""",
            """{"detail":"entities","cell_limit":2}""",
            """{"cell_limit":0}""",
        )) assertFailsWith<IllegalArgumentException> { parseWorldOverview(json(invalid)) }
    }

    @Test
    fun selectorsHaveLengthDelimitedUtf8AndNoSmallStepOrPredicateLimit() {
        val observed = "界".repeat(2048)
        val step = UiStep(true, 7, "Label", observed, null, visible = false)
        val bytes = selectorPayload(listOf(step))
        val input = Buffer().apply { write(bytes) }
        assertEquals(1, input.readIntLe())
        assertEquals(1, input.readIntLe())
        assertEquals(7, input.readIntLe())
        assertEquals(-1, input.readIntLe())
        assertEquals(0, input.readIntLe())
        assertEquals(0, input.readIntLe())
        assertEquals(5, input.readIntLe())
        input.skip(5)
        assertEquals(observed.encodeToByteArray().size, input.readIntLe())
        val path = JsonArray(List(600) { json("""{"axis":"child","match":{"text":"$observed"}}""") })
        val parsed = parseSelector(buildJsonObject { put("path", path) })
        assertEquals(600, parsed.size)
        assertTrue(selectorPayload(parsed).size > 3 * 1024 * 1024)
        assertFailsWith<IllegalArgumentException> { selectorPayload(listOf(step.copy(text = "a\u0000b"))) }
    }

    @Test
    fun chatStringPagesRereadTheSameRetainedMessageWithoutOtherTextPayloads() {
        val record = ChatRecord(9uL, 100uL, 1, 0, "界🚀正文", "[font=default]raw[/font]", playerName = "名字")
        val history = ChatHistory()
        val snapshot = ChatSnapshot(1uL, listOf(0uL, 1uL), listOf(record))
        val first = history.read(snapshot.copy(records = listOf(record.copy(text = "界", raw = "", playerName = "",
            textRange = ObservationTextRange(0, 13, 3, true)))), null, 1, TextSlice(0, 3))
            .getValue("messages").jsonArray.single().jsonObject
        assertEquals("界", first.getValue("text").jsonPrimitive.content)
        assertFalse("raw" in first)
        assertFalse("player_name" in first)
        val before = first.getValue("read_from").jsonObject
        val request = parseChatRead(buildJsonObject { put("offset", before) })
        val second = history.read(snapshot.copy(records = listOf(record.copy(text = "🚀", raw = "", playerName = "",
            textRange = ObservationTextRange(3, 13, 7, true)))), request.offset, 1, TextSlice(3, 4))
            .getValue("messages").jsonArray.single().jsonObject
        assertEquals("🚀", second.getValue("text").jsonPrimitive.content)
        assertEquals(first.getValue("offset"), second.getValue("offset"))
        val raw = history.read(snapshot.copy(records = listOf(record.copy(text = "", playerName = "",
            textRange = ObservationTextRange(0, record.raw.length.toLong(), record.raw.length, false)))),
            request.offset, 1, TextSlice(0, 64, "raw")).getValue("messages").jsonArray.single().jsonObject
        assertEquals(record.raw, raw.getValue("raw").jsonPrimitive.content)
        assertFalse("text" in raw)
    }
}
