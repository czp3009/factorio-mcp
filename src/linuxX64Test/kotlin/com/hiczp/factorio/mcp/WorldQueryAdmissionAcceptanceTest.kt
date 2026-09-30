@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Explicit menu-only admission check, not evidence of a successful in-world query. */
class WorldQueryAdmissionAcceptanceTest {
    @Test
    fun rejectsMissingWorldAndKeepsTheAttachmentUsable() = runBlocking {
        val url = checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")) { "Select an acceptance endpoint" }.toKString()
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")) { "Select an initialized menu-only Factorio client" }
            .toKString().toInt()
        withTimeout(300_000) {
            val client = McpHttpClient(url)
            var attached = false
            try {
                client.initialize()
                assertTrue(
                    client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                        .getValue("attached").jsonPrimitive.boolean
                )
                attached = true
                repeat(2) {
                    val result = client.tool("world_query", buildJsonObject {
                        putJsonObject("selection") { put("kind", "player") }
                    })
                    assertTrue(result["isError"]?.jsonPrimitive?.boolean == true, result.toString())
                    assertTrue(result.getValue("content").jsonArray.any {
                        it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Native world query failed: -2") == true
                    }, result.toString())
                    assertTrue(client.tool("status").toolValue().getValue("attached").jsonPrimitive.boolean)
                    assertTrue(client.tool("ui_read").toolValue().getValue("nodes").jsonArray.isNotEmpty())
                }
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
                println("factorio-mcp world-query admission: live metadata verified, missing world rejected twice, UI/status/detach usable")
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
