@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.io.encoding.Base64
import kotlin.test.*

/** Explicit SDK endpoint/PID capture; the caller owns game and endpoint lifetimes. */
class ScreenshotAcceptanceTest {
    @Test
    fun suspendedRenderingRejectsRepeatedCapturesAndAllowsObservations() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        try {
            withTimeout(600_000) {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                // The operator keeps native rendering suspended throughout this test.
                repeat(2) {
                    val result = withTimeout(30_000) { client.tool("screenshot") }
                    assertTrue(result.getValue("isError").jsonPrimitive.boolean)
                    assertEquals("Screenshot unavailable while rendering is suspended.",
                        result.getValue("structuredContent").jsonObject.getValue("error").jsonPrimitive.content)
                    client.tool("ui_read", buildJsonObject { put("max_nodes", 32) }).toolValue()
                }
                if (getenv("FACTORIO_MCP_TEST_SCREENSHOT_WORLD")?.toKString() == "1") {
                    client.tool("world_query", buildJsonObject {
                        putJsonObject("selection") {
                            put("kind", "tiles")
                            putJsonObject("area") {
                                putJsonObject("left_top") {
                                    put("x", -1)
                                    put("y", -1)
                                }
                                putJsonObject("right_bottom") {
                                    put("x", 1)
                                    put("y", 1)
                                }
                            }
                        }
                        put("limit", 4)
                    }).toolValue()
                }
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

    @Test
    fun capturesConsecutivePngFramesAndDetaches() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val output = environment("FACTORIO_MCP_TEST_SCREENSHOT_PREFIX")
        require(pid > 0 && output.isNotBlank())
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        var attached = false
        try {
            withTimeout(600_000) {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                repeat(2) { index ->
                    val response = client.tool("screenshot")
                    val metadata = response.toolValue()
                    assertEquals("factorio_rendered_frame", metadata.getValue("source").jsonPrimitive.content)
                    assertEquals("game_and_ui", metadata.getValue("scope").jsonPrimitive.content)
                    val width = metadata.getValue("width").jsonPrimitive.int
                    val height = metadata.getValue("height").jsonPrimitive.int
                    assertTrue(width in 1..8192 && height in 1..8192)
                    val image = response.getValue("content").jsonArray.single {
                        it.jsonObject["type"]?.jsonPrimitive?.content == "image"
                    }.jsonObject
                    assertEquals("image/png", image.getValue("mimeType").jsonPrimitive.content)
                    val bytes = Base64.decode(image.getValue("data").jsonPrimitive.content)
                    assertTrue(bytes.size in 33..16 * 1024 * 1024)
                    assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOf(8))
                    fun integer(offset: Int) = (offset until offset + 4).fold(0) { value, i ->
                        (value shl 8) or (bytes[i].toInt() and 255)
                    }
                    assertEquals(width, integer(16))
                    assertEquals(height, integer(20))
                    SystemFileSystem.sink(Path("$output-$index.png")).buffered().use { it.write(bytes) }
                    println("Screenshot $index: ${width}x$height, ${bytes.size} PNG bytes; metadata=$metadata")
                }
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
            }
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
