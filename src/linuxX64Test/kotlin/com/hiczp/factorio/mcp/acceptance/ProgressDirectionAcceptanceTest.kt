@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv

/** Requires the dedicated MCP radio fixture with its unchanged 0.375 progress bar. */
class ProgressDirectionAcceptanceTest {
    @Test
    fun readsNativeDirectionInCompleteAndSelectedSnapshotsAfterReattachment() = runBlocking {
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
                    val full = client.tool("ui_read").toolValue()
                    assertFalse(full.getValue("truncated").jsonPrimitive.boolean)
                    val selected =
                        client
                            .tool(
                                "ui_read",
                                buildJsonObject {
                                    putJsonObject("selector") {
                                        putJsonArray("path") {
                                            add(
                                                buildJsonObject {
                                                    put("axis", "descendant")
                                                    putJsonObject("match") {
                                                        put("native_type", "agui::Window")
                                                        put("text", "MCP radio fixture")
                                                    }
                                                }
                                            )
                                            add(
                                                buildJsonObject {
                                                    put("axis", "descendant")
                                                    putJsonObject("match") {
                                                        put("native_type", "agui::ProgressBar")
                                                    }
                                                }
                                            )
                                        }
                                    }
                                },
                            )
                            .toolValue()
                    assertFalse(selected.getValue("truncated").jsonPrimitive.boolean)
                    val progress =
                        selected
                            .getValue("nodes")
                            .jsonArray
                            .single()
                            .jsonObject
                            .getValue("properties")
                            .jsonObject
                            .getValue("progress")
                            .jsonObject
                    assertEquals("horizontal", progress.getValue("direction").jsonPrimitive.content)
                    assertEquals(0.375, progress.getValue("value").jsonPrimitive.double)
                    val matching =
                        full
                            .getValue("nodes")
                            .jsonArray
                            .mapNotNull { node ->
                                node.jsonObject["properties"]
                                    ?.jsonObject
                                    ?.get("progress")
                                    ?.jsonObject
                            }
                            .filter { it["value"] == progress["value"] }
                    assertEquals(listOf(progress), matching)
                    println(
                        "factorio-mcp progress direction acceptance: $progress, frame=${full["frame"]}"
                    )
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
