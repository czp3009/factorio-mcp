package com.hiczp.factorio.mcp

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

class ScreenshotToolTest {
    @Test
    fun returnsSelectedEncodingAndDimensionsAndRejectsInvalidArgumentsBeforeCapture() = runBlocking {
        withTimeout(30_000) {
            var captures = 0
            val source = RgbImage(7, 5, ByteArray(105) { 90 })
            val game = GameSession {
                object : GameConnection {
                    override suspend fun execute(operation: Int, limit: Int, action: UiAction?): GameSnapshot {
                        if (operation == 6) captures++
                        return GameSnapshot("main_menu", operation != 4, 17,
                            image = source.takeIf { operation == 6 })
                    }

                    override suspend fun isAlive() = true

                    override suspend fun close() = Unit
                }
            }
            val http = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                mcpStreamableHttp { createServer(game) }
            }
            var client: McpHttpClient? = null
            try {
                http.startSuspend(wait = false)
                val endpoint = "http://127.0.0.1:${http.engine.resolvedConnectors().single().port}/mcp"
                val connected = McpHttpClient(endpoint)
                client = connected
                connected.initialize()
                val tools = connected.request("tools/list").getValue("result").jsonObject
                    .getValue("tools").jsonArray
                val properties = tools.single {
                    it.jsonObject.getValue("name").jsonPrimitive.content == "screenshot"
                }.jsonObject.getValue("inputSchema").jsonObject.getValue("properties").jsonObject
                assertEquals(setOf("action", "format", "max_height"), properties.keys)
                assertEquals("jpg", properties.getValue("format").jsonObject.getValue("default").jsonPrimitive.content)
                connected.tool("attach", buildJsonObject { put("pid", 12) }).toolValue()
                for (invalid in listOf(
                    """{"format":"jpeg"}""", """{"format":null}""", """{"format":1}""",
                    """{"max_height":0}""", """{"max_height":-1}""", """{"max_height":1.5}""",
                    """{"max_height":"720"}""", """{"max_height":null}""", """{"max_height":2147483648}""",
                    """{"quality":85}""", """{"region":{}}""",
                    """{"action":"cancel","format":"png"}""", """{"action":"cancel","max_height":720}""",
                )) {
                    assertTrue(connected.tool("screenshot", Json.parseToJsonElement(invalid).jsonObject)
                        .getValue("isError").jsonPrimitive.boolean, invalid)
                }
                assertEquals(0, captures)
                for ((arguments, dimensions) in listOf(
                    "{}" to (7 to 5),
                    """{"max_height":3}""" to (4 to 3),
                    """{"format":"png","max_height":3}""" to (4 to 3),
                    """{"format":"png","max_height":2147483647}""" to (7 to 5),
                )) {
                    val args = Json.parseToJsonElement(arguments).jsonObject
                    val format = args["format"]?.jsonPrimitive?.content ?: "jpg"
                    val result = connected.tool("screenshot", args)
                    val metadata = result.toolValue()
                    assertEquals(metadata, result.getValue("structuredContent"))
                    assertEquals(format, metadata.getValue("format").jsonPrimitive.content)
                    assertEquals(7, metadata.getValue("source_width").jsonPrimitive.int)
                    assertEquals(5, metadata.getValue("source_height").jsonPrimitive.int)
                    assertEquals(dimensions.first, metadata.getValue("width").jsonPrimitive.int)
                    assertEquals(dimensions.second, metadata.getValue("height").jsonPrimitive.int)
                    val content = result.getValue("content").jsonArray.single {
                        it.jsonObject["type"]?.jsonPrimitive?.content == "image"
                    }.jsonObject
                    assertEquals(if (format == "jpg") "image/jpeg" else "image/png",
                        content.getValue("mimeType").jsonPrimitive.content)
                    val decoded = decodeImage(Base64.decode(content.getValue("data").jsonPrimitive.content))
                    assertEquals(dimensions.first, decoded.width)
                    assertEquals(dimensions.second, decoded.height)
                }
                assertEquals(4, captures)
                repeat(2) {
                    assertFalse(connected.tool("screenshot", buildJsonObject { put("action", "cancel") })
                        .toolValue().getValue("cancelled").jsonPrimitive.boolean)
                }
            } finally {
                client?.close()
                http.stopSuspend(0, 1000)
                game.close()
            }
        }
    }
}
