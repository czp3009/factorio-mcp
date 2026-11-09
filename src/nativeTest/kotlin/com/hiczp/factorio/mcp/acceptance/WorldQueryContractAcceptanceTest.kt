@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.worldQuery

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.*

/** Stock/DLC query-capacity scenario; no third-party mods or administrator privileges. */
class WorldQueryContractAcceptanceTest {
    @Test
    fun chatTextSlicesKeepNativeMessageWatermarksAndOriginalUnicode() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        var attached = false
        withTimeout(120_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val before = client.tool("chat_read").toolValue()
                val message = "MCP Unicode 界🚀 [item=iron-plate]"
                client.tool("chat_send", buildJsonObject { put("text", message) }).toolValue()
                val after = client.tool("chat_read", buildJsonObject {
                    put("offset", before.getValue("next_offset"))
                    put("timeout", 30)
                }).toolValue()
                val observed = after.getValue("messages").jsonArray.map { it.jsonObject }
                    .first { it.getValue("raw").jsonPrimitive.content.contains(message) }
                val raw = observed.getValue("raw").jsonPrimitive.content
                val start = raw.substring(0, raw.indexOf("界")).encodeToByteArray().size
                for ((offset, count, expected) in listOf(Triple(start, 3, "界"), Triple(start + 3, 4, "🚀"))) {
                    val sliced = client.tool("chat_read", buildJsonObject {
                        put("offset", observed.getValue("read_from"))
                        put("limit", 1)
                        putJsonObject("text_slice") {
                            put("field", "raw")
                            put("offset", offset)
                            put("limit", count)
                        }
                    }).toolValue().getValue("messages").jsonArray.single().jsonObject
                    assertEquals(expected, sliced.getValue("raw").jsonPrimitive.content)
                    assertEquals(observed.getValue("offset"), sliced.getValue("offset"))
                    assertEquals(raw.encodeToByteArray().size, sliced.getValue("raw_range").jsonObject.getValue("total").jsonPrimitive.int)
                    assertFalse("text" in sliced || "player_name" in sliced)
                }
                println("factorio-mcp chat slices: original UTF-8, unchanged watermarks and omitted unrelated text fields")
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
    }

    @Test
    fun completeValuesDepthBoundsAndCollectionPagesRetainTheirDistinctMeanings() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        fun json(value: String) = Json.parseToJsonElement(value).jsonObject
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                suspend fun query(arguments: String) = client.worldQuery(json(arguments)).toolValue()
                fun JsonObject.row() = getValue("objects").jsonArray.single().jsonObject
                val player = """{"kind":"inspect","target":{"kind":"player"}}"""
                val members = query("""{"selection":$player,"mode":"members"}""").row()
                    .getValue("members").jsonArray.map { it.jsonObject }
                val fields = members.filter { it.getValue("kind").jsonPrimitive.content == "attribute" }
                    .map { it.getValue("name").jsonPrimitive.content }
                assertTrue(fields.size > 64, "Use the current runtime catalog, including attributes beyond the former default page")
                for (explicit in listOf(false, true)) {
                    val result = client.worldQuery(buildJsonObject {
                        put("selection", json(player))
                        if (explicit) putJsonArray("fields") { fields.forEach { add(it) } }
                    }).toolValue()
                    val row = result.row()
                    assertEquals("userdata", row.getValue("value_type").jsonPrimitive.content)
                    val observed = row.getValue("attributes").jsonObject.keys + row.getValue("read_status").jsonObject.keys
                    assertEquals(fields.toSet(), observed)
                    assertNull(row["has_more"])
                    assertNull(result["next_offset"])
                }
                val custom = query("""{"selection":{"kind":"inspect","target":{"kind":"game"},"path":[{"property":"players"}]},"mode":"entries","limit":1}""").row()
                assertTrue(custom.getValue("entries").jsonArray.isNotEmpty())
                assertTrue(custom.getValue("total").jsonPrimitive.int >= 1)

                val path = """[{"property":"gui"},{"property":"screen"},{"index":"factorio_mcp_query_capacity"},{"property":"tags"},{"index":"observation"}]"""
                fun selected(steps: String = path) = """{"kind":"inspect","target":{"kind":"player"},"path":$steps}"""
                val complete = query("""{"selection":${selected()},"depth":32}""")
                val value = complete.row().getValue("value").jsonObject
                assertEquals("ordinary-tag", value.getValue("object_name").jsonPrimitive.content)
                assertEquals(1000, value.getValue("entries").jsonArray.size)
                assertFalse(complete.getValue("truncated").jsonPrimitive.boolean)
                assertNull(complete.row()["entries"])
                val bounded = query("""{"selection":${selected()},"depth":1}""")
                val boundedValue = bounded.row().getValue("value").jsonObject
                assertTrue(boundedValue.getValue("branch").jsonObject.getValue("children_truncated").jsonPrimitive.boolean)
                assertTrue(boundedValue.getValue("empty").jsonObject.isEmpty())
                assertTrue(bounded.getValue("depth_truncated").jsonPrimitive.boolean)
                assertTrue(bounded.getValue("truncated").jsonPrimitive.boolean)
                val entriesPath = path.dropLast(1) + """,{"index":"entries"}]"""
                val entries = query("""{"selection":${selected(entriesPath)},"mode":"entries","limit":1000,"depth":0}""")
                assertFalse(entries.getValue("has_more").jsonPrimitive.boolean)
                assertTrue(entries.getValue("truncated").jsonPrimitive.boolean)
                assertEquals(1000, entries.row().getValue("entries").jsonArray.size)
                val inventory = query("""{"selection":{"kind":"inventory"}}""")
                assertEquals(inventory.getValue("inventory").jsonObject.getValue("size").jsonPrimitive.int,
                    inventory.getValue("objects").jsonArray.size)
                assertFalse(inventory.getValue("has_more").jsonPrimitive.boolean)
                assertTrue(inventory.getValue("objects").jsonArray.size >= 2000)
                val area = """{"left_top":{"x":0,"y":0},"right_bottom":{"x":4,"y":4}}"""
                val spatial = query("""{"selection":{"kind":"tiles","area":$area},"limit":1}""")
                assertFalse(spatial.getValue("selection_complete").jsonPrimitive.boolean)
                assertNull(spatial["has_more"])
                assertNull(spatial["next_position"])
                for (request in listOf(
                    """{"selection":{"kind":"player"},"limit":1}""",
                    """{"selection":$player,"limit":64}""",
                    """{"selection":{"kind":"tiles","area":$area},"start_position":{"x":1,"y":0}}""",
                )) assertTrue(client.worldQuery(json(request))["isError"]?.jsonPrimitive?.boolean == true)
                println("factorio-mcp query contract: ${fields.size} player attributes, native collection, 1000 plain entries, 24-level data, complete inventory and spatial bounds")
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
    }
}
