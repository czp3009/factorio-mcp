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

/** Portable UI contract, explicitly run against an operator-selected endpoint and initialized client. */
class UiReadAcceptanceTest {
    @Test
    fun readsBoundedPortableUiAndReattachesTheResident() = runBlocking {
        val url = checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")) { "Select an acceptance endpoint" }.toKString()
        val pid =
            checkNotNull(getenv("FACTORIO_MCP_TEST_PID")) { "Select an initialized Factorio PID" }.toKString().toInt()
        withTimeout(180_000) {
            val client = McpHttpClient(url)
            var attached = false
            try {
                client.initialize()
                assertFalse(client.tool("status").toolValue().getValue("attached").jsonPrimitive.boolean)
                repeat(2) {
                    val status = client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                    attached = true
                    assertTrue(status.getValue("attached").jsonPrimitive.boolean)
                    assertEquals(pid, status.getValue("pid").jsonPrimitive.int)
                    val bounded = client.tool("ui_read", buildJsonObject { put("max_depth", 0) }).toolValue()
                    assertEquals(1, bounded.getValue("nodes").jsonArray.size)
                    assertTrue(bounded.getValue("truncated").jsonPrimitive.boolean)
                    val complete = client.tool("ui_read", buildJsonObject { put("bounds", true) }).toolValue()
                    assertFalse("visibility_unavailable_reason" in complete)
                    assertEquals("preorder", complete.getValue("order").jsonPrimitive.content)
                    val nodes = complete.getValue("nodes").jsonArray.map { it.jsonObject }
                    assertTrue(nodes.size > 1)
                    nodes.forEachIndexed { index, node ->
                        assertEquals(index, node.getValue("id").jsonPrimitive.int)
                        node["parent"]?.jsonPrimitive?.int?.let { parent ->
                            assertTrue(parent in 0 until index)
                            assertEquals(
                                node.getValue("depth").jsonPrimitive.int - 1,
                                nodes[parent].getValue("depth").jsonPrimitive.int
                            )
                        }
                        assertEquals(setOf("x", "y", "width", "height"), node.getValue("bounds").jsonObject.keys)
                        assertNotNull(node.getValue("visible").jsonPrimitive.booleanOrNull)
                        assertNotNull(node.getValue("hidden_by_search").jsonPrimitive.booleanOrNull)
                        assertNotNull(node.getValue("render_enabled").jsonPrimitive.booleanOrNull)
                    }
                    suspend fun selected(path: JsonArray, maxDepth: Int = 65535) =
                        client.tool("ui_read", buildJsonObject {
                            put("bounds", true)
                            put("max_depth", maxDepth)
                            putJsonObject("selector") { put("path", path) }
                        })

                    val rootPath = buildJsonArray {
                        addJsonObject {
                            put("axis", "child")
                            putJsonObject("match") {}
                            put("position", 1)
                        }
                    }
                    val rootSelected = selected(rootPath).toolValue()
                    assertEquals(nodes.size, rootSelected.getValue("nodes").jsonArray.size)
                    assertEquals(1, rootSelected.getValue("nodes").jsonArray.count {
                        it.jsonObject.getValue("matched").jsonPrimitive.boolean
                    })
                    val limited = selected(rootPath, 0).toolValue()
                    assertEquals(1, limited.getValue("nodes").jsonArray.size)
                    assertTrue(limited.getValue("truncated").jsonPrimitive.boolean)
                    assertEquals(0, limited.getValue("nodes").jsonArray.single().jsonObject.getValue("depth").jsonPrimitive.int)
                    assertTrue(limited.getValue("nodes").jsonArray.single().jsonObject.getValue("children_truncated").jsonPrimitive.boolean)
                    val overlapping = client.tool("ui_read", buildJsonObject {
                        put("max_depth", 1)
                        putJsonArray("fields") {}
                        putJsonObject("selector") {
                            putJsonArray("path") {
                                addJsonObject {
                                    put("axis", "descendant")
                                    putJsonObject("match") {}
                                }
                            }
                        }
                    }).toolValue()
                    val forest = overlapping.getValue("nodes").jsonArray.map { it.jsonObject }
                    val roots = overlapping.getValue("roots").jsonArray.map { it.jsonPrimitive.int }.toSet()
                    assertTrue(roots.size > 1 && forest.size > roots.size)
                    forest.forEachIndexed { index, node ->
                        assertEquals(index, node.getValue("id").jsonPrimitive.int)
                        if (index in roots) {
                            assertEquals(0, node.getValue("depth").jsonPrimitive.int)
                            assertNull(node["parent"])
                        } else {
                            val parent = node.getValue("parent").jsonPrimitive.int
                            assertTrue(parent in 0 until index && parent in roots)
                            assertEquals(1, node.getValue("depth").jsonPrimitive.int)
                        }
                        assertNull(node["text"])
                        assertNull(node["properties"])
                    }
                    val empty = selected(buildJsonArray {
                        addJsonObject {
                            put("axis", "child")
                            putJsonObject("match") {}
                            put("position", Int.MAX_VALUE)
                        }
                    }, 1).toolValue()
                    assertEquals(JsonArray(emptyList()), empty["nodes"])
                    assertEquals(JsonArray(emptyList()), empty["roots"])
                    assertFalse(empty.getValue("truncated").jsonPrimitive.boolean)
                    val clamped = selected(rootPath, Int.MAX_VALUE).toolValue()
                    assertEquals(65535, clamped.getValue("max_depth").jsonPrimitive.int)
                    assertFalse(clamped.getValue("truncated").jsonPrimitive.boolean)
                    assertTrue(client.tool("ui_read", buildJsonObject { put("max_nodes", 1) })
                        .getValue("isError").jsonPrimitive.boolean)
                    // Resolve a leaf by its observed type/text, without assuming a locale or menu caption.
                    val parents = nodes.mapNotNull { it["parent"]?.jsonPrimitive?.int }.toSet()
                    val leaf = nodes.first {
                        it.getValue("id").jsonPrimitive.int !in parents && it["text"]?.jsonPrimitive?.isString == true &&
                                it["text_truncated"]?.jsonPrimitive?.boolean != true
                    }
                    val leafPath = buildJsonArray {
                        addJsonObject {
                            put("axis", "descendant")
                            putJsonObject("match") {
                                put("native_type", leaf.getValue("type"))
                                put("text", leaf.getValue("text"))
                                put("enabled", leaf.getValue("enabled"))
                                put("visible", leaf.getValue("visible"))
                            }
                            put("position", 1)
                        }
                    }
                    val leafSelected = selected(leafPath).toolValue()
                    val matched = leafSelected.getValue("nodes").jsonArray.map { it.jsonObject }
                        .single { it.getValue("matched").jsonPrimitive.boolean }
                    assertEquals(leaf.getValue("type"), matched.getValue("type"))
                    assertEquals(leaf.getValue("text"), matched.getValue("text"))
                    assertEquals(leaf.getValue("visible"), matched.getValue("visible"))
                    assertEquals(leaf.getValue("render_enabled"), matched.getValue("render_enabled"))
                    assertEquals(0, matched.getValue("depth").jsonPrimitive.int)
                    assertNull(matched["parent"])
                    assertNull(matched["children_truncated"])
                    println("factorio-mcp UI acceptance: ${nodes.size} preorder nodes, frame=${complete["frame"]}")
                    assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                    attached = false
                    assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
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
