@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlin.test.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.posix.getenv

/** Explicit stock/DLC acceptance. The operator opens the corresponding native window in a disposable world. */
class UiNativeMetadataAcceptanceTest {
    private suspend fun withClient(test: suspend (McpHttpClient) -> Unit) = withTimeout(600_000) {
        val client = McpHttpClient(checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")).toKString())
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        var attached = false
        try {
            client.initialize()
            client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
            attached = true
            test(client)
        } finally {
            withContext(NonCancellable) {
                try {
                    if (attached) client.tool("detach").toolValue()
                } finally {
                    client.close()
                }
            }
        }
    }

    private fun selector(type: String, position: Int? = null, prototype: String? = null) = buildJsonObject {
        putJsonArray("path") { addJsonObject {
            put("axis", "descendant")
            putJsonObject("match") {
                put("native_type", type)
                prototype?.let { putJsonObject("prototype") { put("name", it) } }
            }
            position?.let { put("position", it) }
        } }
    }

    @Test
    fun readsStoredGraphAndPagesOnlyRequestedSamples() = runBlocking {
        withClient { client ->
            val selector = selector("agui::Graph", 1)
            val full = client.tool("ui_read", buildJsonObject {
                put("selector", selector)
                put("max_depth", 0)
                putJsonArray("fields") { add("properties.graph") }
                putJsonArray("gui_fields") {}
            }).toolValue()
            assertNull(full["graph_unavailable_reason"])
            val graph = full.getValue("nodes").jsonArray.single().jsonObject.getValue("properties").jsonObject.getValue("graph").jsonObject
            assertEquals(0, graph.getValue("index_base").jsonPrimitive.int)
            val series = graph.getValue("series").jsonArray
            assertTrue(series.isNotEmpty(), "Open a native graph with stored series")
            val samples = series.first().jsonObject.getValue("values").jsonArray
            assertTrue(samples.isNotEmpty(), "Open a native graph with stored samples")
            suspend fun page(path: JsonArray, offset: Int, limit: Int) = client.tool("ui_read", buildJsonObject {
                put("selector", selector)
                putJsonObject("collection") {
                    put("source", "graph")
                    put("path", path)
                    put("offset", offset)
                    put("limit", limit)
                }
            }).toolValue().getValue("collection").jsonObject
            val valuesPath = buildJsonArray {
                addJsonObject { put("property", "series") }
                addJsonObject { put("index", 0) }
                addJsonObject { put("property", "values") }
            }
            val first = page(valuesPath, 0, 1)
            assertEquals(samples.size, first.getValue("total").jsonPrimitive.int)
            assertEquals(samples.first(), first.getValue("entries").jsonArray.single().jsonObject.getValue("value"))
            val last = page(valuesPath, samples.lastIndex, 1)
            assertEquals(samples.last(), last.getValue("entries").jsonArray.single().jsonObject.getValue("value"))
            assertFalse(last.getValue("has_more").jsonPrimitive.boolean)
            val beyond = page(valuesPath, Int.MAX_VALUE, 1)
            assertTrue(beyond.getValue("entries").jsonArray.isEmpty())
            assertFalse(beyond.getValue("has_more").jsonPrimitive.boolean)
            val rows = page(buildJsonArray { addJsonObject { put("property", "series") } }, 0, 1)
            assertEquals(series.first(), rows.getValue("entries").jsonArray.single().jsonObject.getValue("value"))
            val invalid = client.tool("ui_read", buildJsonObject {
                put("selector", selector)
                putJsonObject("collection") {
                    put("source", "graph")
                    putJsonArray("path") {
                        addJsonObject { put("property", "series") }
                        addJsonObject { put("index", series.size) }
                        addJsonObject { put("property", "values") }
                    }
                }
            })
            assertTrue(invalid.getValue("isError").jsonPrimitive.boolean)
            assertEquals(samples.first(), page(valuesPath, 0, 1).getValue("entries").jsonArray.single().jsonObject.getValue("value"))
            println("Stored graph: ${series.size} series; first series has ${samples.size} samples; native pages and error recovery passed")
        }
    }

    @Test
    fun readsConnectionPrototypeIdentityThroughSelectors() = runBlocking {
        withClient { client ->
            val type = "UniverseWidget::SpaceConnectionWidget"
            val result = client.tool("ui_read", buildJsonObject {
                put("selector", selector(type))
                put("max_depth", 0)
                putJsonArray("fields") { add("properties.prototype") }
                putJsonArray("gui_fields") {}
            }).toolValue()
            assertNull(result["stored_identity_unavailable_reason"])
            val nodes = result.getValue("nodes").jsonArray
            assertTrue(nodes.isNotEmpty(), "Open the native starmap with connection widgets")
            val identities = nodes.map { it.jsonObject.getValue("properties").jsonObject.getValue("prototype").jsonObject }
            for (identity in identities) {
                assertTrue(identity.getValue("name").jsonPrimitive.content.isNotEmpty())
                assertEquals("SpaceConnectionPrototype", identity.getValue("native_type").jsonPrimitive.content)
            }
            val name = identities.first().getValue("name").jsonPrimitive.content
            val selected = client.tool("ui_read", buildJsonObject {
                put("selector", selector(type, 1, name))
                put("max_depth", 0)
                putJsonArray("fields") { add("properties.prototype") }
            }).toolValue()
            assertEquals(identities.first(), selected.getValue("nodes").jsonArray.single().jsonObject.getValue("properties").jsonObject.getValue("prototype"))
            println("Connection identities: ${JsonArray(identities)}")
        }
    }

    @Test
    fun readsAchievementIdentityStateDescriptionsAndProgress() = runBlocking {
        withClient { client ->
            val result = client.tool("ui_read", buildJsonObject {
                put("selector", selector("AchievementCard"))
                putJsonArray("fields") {
                    add("text")
                    add("properties.prototype")
                    add("properties.achievement")
                    add("properties.progress")
                }
                putJsonArray("gui_fields") {}
            }).toolValue()
            assertNull(result["stored_identity_unavailable_reason"])
            val nodes = result.getValue("nodes").jsonArray.map { it.jsonObject }
            val cards = nodes.filter { it.getValue("type") == JsonPrimitive("AchievementCard") }
            assertTrue(cards.isNotEmpty(), "Open the stock achievement window")
            for (card in cards) {
                val properties = card.getValue("properties").jsonObject
                val prototype = properties.getValue("prototype").jsonObject
                assertTrue(prototype.getValue("name").jsonPrimitive.content.isNotEmpty())
                assertTrue(prototype.getValue("native_type").jsonPrimitive.content.endsWith("AchievementPrototype"))
                val state = properties.getValue("achievement").jsonObject
                assertEquals("AchievementState", state.getValue("state_type").jsonPrimitive.content)
                assertNotNull(state.getValue("state_value").jsonPrimitive.intOrNull)
                assertNotNull(state.getValue("shown_state_value").jsonPrimitive.intOrNull)
                assertNotNull(state.getValue("in_sidebar").jsonPrimitive.booleanOrNull)
            }
            assertTrue(nodes.any { it["text"]?.jsonPrimitive?.content?.isNotEmpty() == true })
            val first = cards.first().getValue("properties").jsonObject.getValue("prototype").jsonObject
            val selected = client.tool("ui_read", buildJsonObject {
                put("selector", selector("AchievementCard", 1, first.getValue("name").jsonPrimitive.content))
                put("max_depth", 0)
                putJsonArray("fields") { add("properties.achievement") }
            }).toolValue()
            assertNotNull(selected.getValue("nodes").jsonArray.single().jsonObject.getValue("properties").jsonObject["achievement"])
            println("Read ${cards.size} original achievement cards, ${nodes.count { it["properties"]?.jsonObject?.containsKey("progress") == true }} progress widgets and native descriptions")
        }
    }
}
