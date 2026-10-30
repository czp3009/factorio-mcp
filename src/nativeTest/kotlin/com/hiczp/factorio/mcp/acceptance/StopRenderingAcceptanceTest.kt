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
import kotlin.test.*

/** The operator observes drawing/native state and keeps the test window unminimized. */
class StopRenderingAcceptanceTest {
    @Test
    fun stopsOnceAndKeepsTheFrontendAvailableAcrossDetach() = runBlocking {
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
                        client.tool("stop_rendering").toolValue())
                    val capture = withTimeout(30_000) { client.tool("screenshot") }
                    assertTrue(capture.getValue("isError").jsonPrimitive.boolean)
                    assertEquals("Screenshot unavailable while rendering is suspended.",
                        capture.getValue("structuredContent").jsonObject.getValue("error").jsonPrimitive.content)
                    client.tool("ui_read", buildJsonObject { put("max_nodes", 2000) }).toolValue()
                    client.tool("input_bindings", buildJsonObject { put("limit", 4) }).toolValue()
                }
                val after = client.tool("status").toolValue()
                assertEquals(before["state"], after["state"])
                assertEquals(before["paused"], after["paused"])
                if (before["state"]?.jsonPrimitive?.content in setOf("in_game", "paused")) {
                    client.tool("world_query", buildJsonObject {
                        putJsonObject("selection") { put("kind", "player") }
                    }).toolValue()
                }
                client.tool("detach").toolValue()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val capture = withTimeout(30_000) { client.tool("screenshot") }
                assertTrue(capture.getValue("isError").jsonPrimitive.boolean)
                client.tool("ui_read", buildJsonObject { put("max_nodes", 32) }).toolValue()
                println("One-shot stop completed twice; screenshot rejection, UI/bindings, state and detach/reattach verified: $after")
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
}
