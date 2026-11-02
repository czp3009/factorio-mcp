@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.decodeImage
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
import kotlin.math.roundToInt
import kotlin.test.*

/** Explicit SDK endpoint/PID capture; the caller owns game and endpoint lifetimes. */
class ScreenshotAcceptanceTest {
    @Test
    fun capturesJpgWithQualityAndHeightLimitedFrames() = runBlocking<Unit> {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val prefix = environment("FACTORIO_MCP_TEST_SCREENSHOT_PREFIX")
        try {
            withTimeout(600_000) {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val cases = listOf(
                    "png-original" to buildJsonObject { put("format", "png") },
                    "jpg-default" to buildJsonObject {},
                    "jpg-quality-1" to buildJsonObject { put("quality", 1) },
                    "jpg-quality-85" to buildJsonObject { put("quality", 85) },
                    "jpg-quality-100" to buildJsonObject { put("quality", 100) },
                    "jpg-720" to buildJsonObject {
                        put("format", "jpg")
                        put("max_height", 720)
                        put("quality", 60)
                    },
                    "png-720" to buildJsonObject {
                        put("format", "png")
                        put("max_height", 720)
                    },
                    "jpg-no-enlargement" to buildJsonObject { put("max_height", Int.MAX_VALUE) },
                )
                for ((name, arguments) in cases) {
                    val response = client.tool("screenshot", arguments)
                    val metadata = response.toolValue()
                    val sourceWidth = metadata.getValue("source_width").jsonPrimitive.int
                    val sourceHeight = metadata.getValue("source_height").jsonPrimitive.int
                    val width = metadata.getValue("width").jsonPrimitive.int
                    val height = metadata.getValue("height").jsonPrimitive.int
                    val requestedHeight = arguments["max_height"]?.jsonPrimitive?.int ?: sourceHeight
                    assertEquals(minOf(sourceHeight, requestedHeight), height)
                    assertEquals(maxOf(1, (sourceWidth.toDouble() * height / sourceHeight).roundToInt()), width)
                    val format = arguments["format"]?.jsonPrimitive?.content ?: "jpg"
                    assertEquals(format, metadata.getValue("format").jsonPrimitive.content)
                    if (format == "jpg") {
                        assertEquals(arguments["quality"]?.jsonPrimitive?.int ?: 85,
                            metadata.getValue("quality").jsonPrimitive.int)
                    } else {
                        assertFalse("quality" in metadata)
                    }
                    val content = response.getValue("content").jsonArray.single {
                        it.jsonObject["type"]?.jsonPrimitive?.content == "image"
                    }.jsonObject
                    assertEquals(if (format == "jpg") "image/jpeg" else "image/png",
                        content.getValue("mimeType").jsonPrimitive.content)
                    val bytes = Base64.decode(content.getValue("data").jsonPrimitive.content)
                    val decoded = decodeImage(bytes)
                    assertEquals(width, decoded.width)
                    assertEquals(height, decoded.height)
                    SystemFileSystem.sink(Path("$prefix-$name.$format")).buffered().use { it.write(bytes) }
                    println("Screenshot $name: ${width}x$height from ${sourceWidth}x$sourceHeight, ${bytes.size} bytes; metadata=$metadata")
                }
                for (arguments in listOf(
                    buildJsonObject { put("quality", 0) },
                    buildJsonObject { put("quality", 101) },
                    buildJsonObject {
                        put("format", "png")
                        put("quality", 85)
                    },
                    buildJsonObject {
                        put("action", "cancel")
                        put("quality", 85)
                    },
                )) {
                    assertTrue(client.tool("screenshot", arguments).getValue("isError").jsonPrimitive.boolean)
                }
                client.tool("detach").toolValue()
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
                    val result = withTimeout(30_000) { client.tool("screenshot", buildJsonObject { put("format", "png") }) }
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
                    val response = client.tool("screenshot", buildJsonObject { put("format", "png") })
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
