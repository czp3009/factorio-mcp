package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class UiGraphTest {
    private fun collection(path: String) = parseUiCollection(Json.parseToJsonElement("""{"source":"graph","path":$path,"offset":1,"limit":2}"""))

    @Test
    fun selectsExistingGraphCollectionsWithoutArbitraryPaths() {
        assertEquals(1, collection("""[{"property":"series"}]""").graphCollection)
        assertEquals(2, collection("""[{"property":"series"},{"index":7},{"property":"values"}]""").graphCollection)
        assertEquals(7, collection("""[{"property":"series"},{"index":7},{"property":"values"}]""").graphSeries)
        for (path in listOf("[]", """[{"property":"unknown"}]""", """[{"property":"series"},{"index":-1},{"property":"values"}]""",
            """[{"property":"series"},{"index":0.5},{"property":"values"}]""")) assertFails { collection(path) }
    }

    @Test
    fun retainsRawNumbersRichTextOrderAndNonFiniteValues() {
        val graph = WidgetGraph(3, -2.0, 4.0,
            listOf(WidgetGraphSeries(0, "[entity=original] 原文", listOf(1.0, Double.NaN, Double.POSITIVE_INFINITY))))
        val json = graph.json()
        assertEquals("[entity=original] 原文", json.getValue("series").jsonArray.single().jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals("nan", json.getValue("series").jsonArray.single().jsonObject.getValue("values").jsonArray[1].jsonObject.getValue("value").jsonPrimitive.content)
        val page = graph.copy(collection = 2, series = listOf(WidgetGraphSeries(0, "label", listOf(2.0), 3, 1)), total = 3, offset = 1).collectionJson()
        assertEquals(2, page.getValue("next_offset").jsonPrimitive.int)
        assertEquals(1, page.getValue("entries").jsonArray.single().jsonObject.getValue("key").jsonPrimitive.int)
        assertFalse(graph.copy(collection = 1, series = emptyList(), total = 1, offset = 9).collectionJson().getValue("has_more").jsonPrimitive.boolean)
    }

    @Test
    fun graphAndAchievementPropertiesFollowFieldSelection() {
        val node = WidgetSnapshot(0, "AchievementCard", "", true, 0, 0, 1, 1, false,
            properties = WidgetProperties(achievement = WidgetAchievement(1, 2, false)))
        val snapshot = GameSnapshot("in_game", true, 1, listOf(node))
        val properties = snapshot.uiJson(false).getValue("nodes").jsonArray.single().jsonObject.getValue("properties").jsonObject
        assertEquals(1, properties.getValue("achievement").jsonObject.getValue("state_value").jsonPrimitive.int)
        assertNull(snapshot.uiJson(false, fields = emptySet()).getValue("nodes").jsonArray.single().jsonObject["properties"])
    }
}
