@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv

/** Requires a disposable UI fixture containing left/right/none switches with allow_none enabled. */
class SwitchStateAcceptanceTest {
    @Test
    fun readsNativeSwitchIdentifiersAfterReattachment() = runBlocking {
        val url = checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")).toKString()
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        withTimeout(600_000) {
            val client = McpHttpClient(url)
            var attached = false
            try {
                client.initialize()
                repeat(2) {
                    client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                    attached = true
                    val snapshot = client.tool("ui_read").toolValue()
                    assertFalse(snapshot.getValue("truncated").jsonPrimitive.boolean)
                    val switches =
                        snapshot.getValue("nodes").jsonArray.mapNotNull { node ->
                            node.jsonObject["properties"]?.jsonObject?.get("switch")?.jsonObject
                        }
                    assertEquals(3, switches.size)
                    assertEquals(
                        setOf("left", "right", "none"),
                        switches.map { it.getValue("state").jsonPrimitive.content }.toSet(),
                    )
                    val values = switches.map { it.getValue("state_value").jsonPrimitive.int }
                    assertEquals(3, values.distinct().size)
                    assertTrue(values.all { it in 0..255 })
                    assertTrue(switches.all { it.getValue("allow_none").jsonPrimitive.boolean })
                    println("factorio-mcp Switch acceptance: $switches, frame=${snapshot["frame"]}")
                    assertFalse(
                        client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean
                    )
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
}
