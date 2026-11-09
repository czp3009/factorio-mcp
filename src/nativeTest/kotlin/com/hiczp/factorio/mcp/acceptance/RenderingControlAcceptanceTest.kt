@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.worldQuery

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlin.io.encoding.Base64
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.*

/** Keep the test window unminimized; restoration must occur through the tool alone. */
class RenderingControlAcceptanceTest {
    @Test
    fun stopsAndResumesWithoutWindowEventsAcrossDetach() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        try {
            withTimeout(600_000) {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val before = client.tool("status").toolValue()
                repeat(2) {
                    assertEquals(buildJsonObject { put("dispatch", "completed") },
                        client.tool("set_rendering", buildJsonObject { put("enabled", false) }).toolValue())
                    val capture = withTimeout(30_000) { client.tool("screenshot", buildJsonObject { put("format", "png") }) }
                    assertTrue(capture.getValue("isError").jsonPrimitive.boolean)
                    assertEquals("Screenshot unavailable while rendering is suspended.",
                        capture.getValue("structuredContent").jsonObject.getValue("error").jsonPrimitive.content)
                    client.tool("ui_read", buildJsonObject { put("max_depth", 2) }).toolValue()
                    client.tool("input_bindings", buildJsonObject { put("limit", 4) }).toolValue()
                }
                val after = client.tool("status").toolValue()
                assertEquals(before["state"], after["state"])
                assertEquals(before["paused"], after["paused"])
                if (before["state"]?.jsonPrimitive?.content in setOf("in_game", "paused")) {
                    client.worldQuery(buildJsonObject {
                        putJsonObject("selection") { put("kind", "player") }
                    }).toolValue()
                }
                client.tool("detach").toolValue()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val capture = withTimeout(30_000) { client.tool("screenshot", buildJsonObject { put("format", "png") }) }
                assertTrue(capture.getValue("isError").jsonPrimitive.boolean)
                client.tool("ui_read", buildJsonObject { put("max_depth", 1) }).toolValue()
                repeat(2) {
                    assertEquals(buildJsonObject { put("dispatch", "completed") },
                        client.tool("set_rendering", buildJsonObject { put("enabled", true) }).toolValue())
                }
                repeat(2) {
                    val response = withTimeout(30_000) { client.tool("screenshot", buildJsonObject { put("format", "png") }) }
                    val metadata = response.toolValue()
                    assertEquals("factorio_rendered_frame", metadata.getValue("source").jsonPrimitive.content)
                    assertTrue(metadata.getValue("width").jsonPrimitive.int > 0)
                    assertTrue(metadata.getValue("height").jsonPrimitive.int > 0)
                    val image = response.getValue("content").jsonArray.single {
                        it.jsonObject["type"]?.jsonPrimitive?.content == "image"
                    }.jsonObject
                    assertEquals("image/png", image.getValue("mimeType").jsonPrimitive.content)
                    val bytes = Base64.decode(image.getValue("data").jsonPrimitive.content)
                    assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOf(8))
                    val prefix = getenv("FACTORIO_MCP_TEST_SCREENSHOT_PREFIX")?.toKString()
                    if (prefix != null) {
                        SystemFileSystem.sink(Path("$prefix-resumed-$it.png")).buffered().use { it.write(bytes) }
                    }
                    println("Resumed PNG $it: $metadata, ${bytes.size} bytes")
                }
                val resumed = client.tool("status").toolValue()
                assertEquals(before["state"], resumed["state"])
                assertEquals(before["paused"], resumed["paused"])
                client.tool("detach").toolValue()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                withTimeout(30_000) { client.tool("screenshot", buildJsonObject { put("format", "png") }) }.toolValue()
                println("Repeated stop/resume, native screenshot rejection, UI/world reads, state and both detach/reattach states verified: $resumed")
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    client.tool("detach").toolValue()
                } finally {
                    client.close()
                }
            }
        }
    }

    /** Run explicitly with the window minimized and MCP rendering enabled initially. */
    @Test
    fun enablingPreservesNativeMinimizationPolicy() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        try {
            withTimeout(120_000) {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", environment("FACTORIO_MCP_TEST_PID").toInt()) }).toolValue()
                suspend fun capture() = withTimeout(30_000) {
                    client.tool("screenshot", buildJsonObject { put("format", "png") })
                }
                fun suspended(result: JsonObject): Boolean {
                    if (result["isError"]?.jsonPrimitive?.boolean != true) {
                        assertTrue(result.getValue("content").jsonArray.any {
                            it.jsonObject["type"]?.jsonPrimitive?.content == "image"
                        })
                        return false
                    }
                    assertEquals("Screenshot unavailable while rendering is suspended.",
                        result.getValue("structuredContent").jsonObject.getValue("error").jsonPrimitive.content)
                    return true
                }
                val initiallySuspended = suspended(capture())
                for (enabled in listOf(false, true, true)) {
                    client.tool("set_rendering", buildJsonObject { put("enabled", enabled) }).toolValue()
                    assertEquals(!enabled || initiallySuspended, suspended(capture()))
                    client.tool("ui_read", buildJsonObject { put("max_depth", 1) }).toolValue()
                }
                println("Enabling preserved the game's minimized presentation policy: initially_suspended=$initiallySuspended")
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    try {
                        client.tool("set_rendering", buildJsonObject { put("enabled", true) }).toolValue()
                    } finally {
                        client.tool("detach").toolValue()
                    }
                } finally {
                    client.close()
                }
            }
        }
    }
}
