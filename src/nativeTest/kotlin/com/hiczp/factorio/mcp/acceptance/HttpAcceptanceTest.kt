@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.uiSelector
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.posix.getenv

/** Opt-in real-game acceptance reuses an explicitly selected process and endpoint. */
class HttpAcceptanceTest {
    @Test
    fun independentSessionsCanCloseAndReconnect() = runBlocking {
        val url =
            getenv("FACTORIO_MCP_ACCEPTANCE_URL")?.toKString()?.takeIf { it.isNotBlank() }
                ?: return@runBlocking

        suspend fun cycle() {
            val client = McpHttpClient(url)
            try {
                client.initialize()
                val result =
                    client.request("tools/list", requestId = 1_000).getValue("result").jsonObject
                assertTrue(result.getValue("tools").jsonArray.isNotEmpty())
            } finally {
                client.close()
            }
        }
        withTimeout(30_000) {
            repeat(12) { cycle() }
            coroutineScope { List(4) { async { repeat(4) { cycle() } } }.awaitAll() }
            cycle()
        }
    }

    @Test
    fun allAdvertisedToolsAndRepeatedAttachment() = runBlocking {
        val url =
            getenv("FACTORIO_MCP_ACCEPTANCE_URL")?.toKString()?.takeIf { it.isNotBlank() }
                ?: return@runBlocking
        val pid =
            getenv("FACTORIO_MCP_TEST_PID")?.toKString()?.toInt()
                ?: error("Set FACTORIO_MCP_TEST_PID for game acceptance")
        withTimeout(120_000) {
            val client = McpHttpClient(url)
            val other = McpHttpClient(url)
            try {
                client.initialize()
                other.initialize()
                val names =
                    client
                        .request("tools/list")
                        .getValue("result")
                        .jsonObject
                        .getValue("tools")
                        .jsonArray
                        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
                        .toSet()
                assertEquals(
                    setOf(
                        "status",
                        "attach",
                        "detach",
                        "ui_read",
                        "ui_action",
                        "screenshot",
                        "input_bindings",
                        "world_query",
                        "world_overview",
                        "chat_read",
                        "chat_send",
                        "input",
                    ),
                    names,
                )
                client.tool("detach").toolValue()
                assertFalse(
                    client.tool("status").toolValue().getValue("attached").jsonPrimitive.boolean
                )
                assertTrue(client.tool("ui_read").getValue("isError").jsonPrimitive.boolean)
                assertTrue(client.tool("screenshot").getValue("isError").jsonPrimitive.boolean)
                assertTrue(client.tool("input_bindings").getValue("isError").jsonPrimitive.boolean)
                assertTrue(client.tool("world_overview").getValue("isError").jsonPrimitive.boolean)
                assertTrue(client.tool("chat_read").getValue("isError").jsonPrimitive.boolean)
                assertTrue(
                    client
                        .tool("chat_send", buildJsonObject { put("text", "MCP detached probe") })
                        .getValue("isError")
                        .jsonPrimitive
                        .boolean
                )
                assertTrue(
                    client
                        .tool("input", buildJsonObject { putJsonArray("timeline") {} })
                        .getValue("isError")
                        .jsonPrimitive
                        .boolean
                )
                val playerQuery = buildJsonObject {
                    putJsonObject("selection") { put("kind", "player") }
                }
                assertTrue(
                    client
                        .tool("world_query", playerQuery)
                        .getValue("isError")
                        .jsonPrimitive
                        .boolean
                )
                val absentAction = buildJsonObject {
                    put("action", "click")
                    put("selector", uiSelector("No such MCP acceptance widget"))
                }
                assertTrue(
                    client.tool("ui_action", absentAction).getValue("isError").jsonPrimitive.boolean
                )
                assertTrue(client.tool("attach").getValue("isError").jsonPrimitive.boolean)
                assertTrue(
                    client
                        .tool("status", buildJsonObject { put("pid", pid) })
                        .getValue("isError")
                        .jsonPrimitive
                        .boolean
                )
                val target = buildJsonObject { put("pid", pid) }
                repeat(3) {
                    assertTrue(
                        client
                            .tool("attach", target)
                            .toolValue()
                            .getValue("attached")
                            .jsonPrimitive
                            .boolean
                    )
                    client.tool("attach", target).toolValue()
                    assertEquals(
                        pid,
                        other.tool("status").toolValue().getValue("pid").jsonPrimitive.int,
                    )
                    val full = client.tool("ui_read").toolValue()
                    val world = client.tool("world_query", playerQuery)
                    if (full["state"]!!.jsonPrimitive.content == "main_menu") {
                        assertTrue(world["isError"]!!.jsonPrimitive.boolean)
                    } else {
                        assertTrue(
                            world.toolValue()["player"]!!.jsonObject["index"]!!.jsonPrimitive.int >
                                0
                        )
                    }
                    assertTrue(
                        client
                            .tool(
                                "ui_action",
                                buildJsonObject {
                                    put("action", "press_key")
                                    put("key", "NO_SUCH_KEY")
                                },
                            )
                            .getValue("isError")
                            .jsonPrimitive
                            .boolean
                    )
                    val bindings =
                        client
                            .tool(
                                "input_bindings",
                                buildJsonObject {
                                    putJsonArray("ids") {
                                        add("build")
                                        add("confirm-gui")
                                        add("__mcp_absent_control__")
                                    }
                                },
                            )
                            .toolValue()
                    assertTrue(bindings.getValue("snapshot_complete").jsonPrimitive.boolean)
                    assertEquals(
                        setOf("build", "confirm-gui"),
                        bindings
                            .getValue("controls")
                            .jsonArray
                            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
                            .toSet(),
                    )
                    assertEquals(
                        "__mcp_absent_control__",
                        bindings.getValue("missing_ids").jsonArray.single().jsonPrimitive.content,
                    )
                    assertTrue(
                        client
                            .tool("ui_action", absentAction)
                            .getValue("isError")
                            .jsonPrimitive
                            .boolean
                    )
                    val screenshot = client.tool("screenshot")
                    val cancelled =
                        client
                            .tool("screenshot", buildJsonObject { put("action", "cancel") })
                            .toolValue()
                    assertEquals("completed", cancelled.getValue("dispatch").jsonPrimitive.content)
                    assertFalse(cancelled.getValue("cancelled").jsonPrimitive.boolean)
                    val metadata = screenshot.toolValue()
                    assertEquals("game_and_ui", metadata.getValue("scope").jsonPrimitive.content)
                    assertTrue(metadata.getValue("width").jsonPrimitive.int > 0)
                    assertTrue(metadata.getValue("height").jsonPrimitive.int > 0)
                    val image =
                        screenshot
                            .getValue("content")
                            .jsonArray
                            .single { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }
                            .jsonObject
                    assertEquals("image/png", image.getValue("mimeType").jsonPrimitive.content)
                    val bytes = Base64.decode(image.getValue("data").jsonPrimitive.content)
                    assertContentEquals(
                        byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
                        bytes.take(8).toByteArray(),
                    )
                    assertFalse(full.getValue("truncated").jsonPrimitive.boolean)
                    val nodes = full.getValue("nodes").jsonArray
                    assertTrue(nodes.isNotEmpty())
                    assertTrue(
                        nodes.any {
                            it.jsonObject.getValue("type").jsonPrimitive.content ==
                                "agui::TopContainer"
                        }
                    )
                    val bounded =
                        client.tool("ui_read", buildJsonObject { put("max_nodes", 1) }).toolValue()
                    assertEquals(1, bounded.getValue("nodes").jsonArray.size)
                    assertTrue(bounded.getValue("truncated").jsonPrimitive.boolean)
                    assertTrue(
                        client
                            .tool("ui_read", buildJsonObject { put("max_nodes", 0) })
                            .getValue("isError")
                            .jsonPrimitive
                            .boolean
                    )
                    client.tool("detach").toolValue()
                    assertFalse(
                        other.tool("status").toolValue().getValue("attached").jsonPrimitive.boolean
                    )
                    client.tool("detach").toolValue()
                }
            } finally {
                other.close()
                client.close()
            }
        }
    }
}
