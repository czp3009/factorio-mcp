@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit loaded-world observation only. Repeat in prepared normal/remote/overlay states separately. */
class ViewportAcceptanceTest {
    @Test
    fun readsNativeBoundsAndUsesThemForDefaultAndExplicitAreas() = runBlocking {
        val endpoint = checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")).toKString()
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        val client = McpHttpClient(endpoint)
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val player = client.tool("world_query", buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "inspect")
                        putJsonObject("target") { put("kind", "player") }
                    }
                    put("mode", "values")
                    putJsonArray("fields") { add("display_resolution") }
                }).toolValue()
                val resolution = player.getValue("objects").jsonArray.single().jsonObject
                    .getValue("attributes").jsonObject.getValue("display_resolution").jsonObject
                val overview = client.tool("world_overview", buildJsonObject {
                    put("detail", "entities")
                    put("entity_limit", 1)
                    putJsonArray("fields") { add("name") }
                }).toolValue()
                val viewport = overview.getValue("viewport").jsonObject
                assertTrue(viewport.getValue("available").jsonPrimitive.boolean)
                assertEquals("client_content_pixels", viewport.getValue("coordinate_space").jsonPrimitive.content)
                assertEquals("not_evaluated", viewport.getValue("occlusion").jsonPrimitive.content)
                for (axis in listOf("width", "height")) {
                    assertTrue(viewport.getValue(axis).jsonPrimitive.int in 2..32768)
                    assertEquals(resolution.getValue(axis), viewport.getValue(axis))
                }
                val area = viewport.getValue("area").jsonObject
                val left = area.getValue("left_top").jsonObject
                val right = area.getValue("right_bottom").jsonObject
                for (axis in listOf("x", "y")) {
                    val minimum = left.getValue(axis).jsonPrimitive.double
                    val maximum = right.getValue(axis).jsonPrimitive.double
                    assertTrue(minimum.isFinite() && maximum.isFinite() && maximum > minimum)
                }
                assertEquals(area, overview.getValue("area"))
                assertEquals(viewport.getValue("surface_index"), overview.getValue("surface").jsonObject.getValue("index"))
                val explicit = client.tool("world_overview", buildJsonObject {
                    put("detail", "entities")
                    put("area", area)
                    put("surface", viewport.getValue("surface_index"))
                    put("entity_limit", 1)
                    putJsonArray("fields") { add("name") }
                }).toolValue()
                assertEquals(area, explicit.getValue("area"))
                assertEquals(overview.getValue("surface"), explicit.getValue("surface"))
                println("factorio-mcp viewport: $viewport")
                println("factorio-mcp viewport: dimensions match LuaPlayer.display_resolution; ordered map coordinates, surface and default/explicit overview areas verified")
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
