package com.hiczp.factorio.mcp

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpTransportTest {
    @Test
    fun screenshotCancellationBypassesTheQueueAcrossHttpSessions() = runBlocking {
        withTimeout(15000) {
            val started = CompletableDeferred<Unit>()
            var cleaned = false
            val game = GameSession {
                object : GameConnection {
                    override suspend fun execute(operation: Int, limit: Int, action: UiAction?): GameSnapshot {
                        if (operation == 6) {
                            started.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cleaned = true
                            }
                        }
                        return GameSnapshot("main_menu", operation != 4, 1)
                    }
                    override suspend fun isAlive() = true

                    override suspend fun query(query: WorldQuery) = GameSnapshot("in_game", true, 1,
                        worldJson = """{"player":{"zoom":1.10408951367},"objects":[],"truncated":false}""")

                    override suspend fun close() = Unit
                }
            }
            val http = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                mcpStreamableHttp { createServer(game) }
            }
            val clients = mutableListOf<McpHttpClient>()
            try {
                http.startSuspend(wait = false)
                val endpoint = "http://127.0.0.1:${http.engine.resolvedConnectors().single().port}/mcp"
                repeat(2) { clients += McpHttpClient(endpoint).also { it.initialize() } }
                val first = clients[0]
                val second = clients[1]
                val attached = first.tool("attach", buildJsonObject { put("pid", 12) })
                assertEquals(attached.toolValue(), attached.getValue("structuredContent"))
                val world = first.tool("world_query", buildJsonObject {
                    putJsonObject("selection") { put("kind", "player") }
                }).toolValue()
                assertEquals(1.10408951367, world.getValue("player").jsonObject.getValue("zoom").jsonPrimitive.double)
                val capture = async { first.tool("screenshot") }
                started.await()
                val busy = second.tool("screenshot")
                assertTrue(busy.getValue("isError").jsonPrimitive.boolean)
                assertEquals(Json.parseToJsonElement(busy.getValue("content").jsonArray.first().jsonObject
                    .getValue("text").jsonPrimitive.content), busy.getValue("structuredContent"))
                assertTrue(busy.getValue("structuredContent").jsonObject.getValue("error").jsonPrimitive.content.contains("active"))
                val cancelled = second.tool("screenshot", buildJsonObject { put("action", "cancel") })
                assertTrue(cancelled.toolValue().getValue("cancelled").jsonPrimitive.boolean)
                assertTrue(cleaned)
                assertTrue(capture.await().getValue("isError").jsonPrimitive.boolean)
                assertFalse(second.tool("screenshot", buildJsonObject { put("action", "cancel") })
                    .toolValue().getValue("cancelled").jsonPrimitive.boolean)
                assertEquals("main_menu", second.tool("status").toolValue().getValue("state").jsonPrimitive.content)
            } finally {
                withContext(NonCancellable) {
                    game.close()
                    clients.forEach { it.close() }
                    http.stopSuspend(0, 1000)
                }
            }
        }
    }

    @Test
    fun cioClientUsesTheSdkStreamableHttpSession() = runBlocking {
        withTimeout(15000) {
            val protocol = Server(
                Implementation("factorio-mcp-http-test", "test"),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
            )
            val http = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                mcpStreamableHttp { protocol }
            }
            try {
                http.startSuspend(wait = false)
                val port = http.engine.resolvedConnectors().single().port
                val client = McpHttpClient("http://127.0.0.1:$port/mcp")
                try {
                    client.initialize()
                    val result = client.request("tools/list").getValue("result").jsonObject
                    assertTrue(result.getValue("tools").jsonArray.isEmpty())
                } finally {
                    withContext(NonCancellable) { client.close() }
                }
            } finally {
                withContext(NonCancellable) { http.stopSuspend(0, 1000) }
            }
        }
    }

    @Test
    fun acceptanceClientRejectsHttpsBeforeCreatingAnEngine() {
        assertFailsWith<IllegalArgumentException> { McpHttpClient("https://127.0.0.1/mcp") }
    }
}
