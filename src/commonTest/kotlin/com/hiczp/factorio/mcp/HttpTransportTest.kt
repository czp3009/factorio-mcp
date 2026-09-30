package com.hiczp.factorio.mcp

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpTransportTest {
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
