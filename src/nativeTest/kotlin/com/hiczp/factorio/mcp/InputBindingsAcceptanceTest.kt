@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.*

/** Read-only acceptance against one explicitly selected initialized game and HTTP endpoint. */
class InputBindingsAcceptanceTest {
    @Test
    fun readsKeyboardMouseBindingsWithPortableFilteringAndPagination() = runBlocking {
        val url = checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")) { "Select an acceptance endpoint" }.toKString()
        val pid =
            checkNotNull(getenv("FACTORIO_MCP_TEST_PID")) { "Select an initialized Factorio PID" }.toKString().toInt()
        withTimeout(240_000) {
            val client = McpHttpClient(url)
            var attached = false
            try {
                client.initialize()
                assertFalse(client.tool("status").toolValue().getValue("attached").jsonPrimitive.boolean)
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val complete = client.tool("input_bindings", buildJsonObject { put("limit", 1024) }).toolValue()
                val rows = complete.getValue("controls").jsonArray.map { it.jsonObject }
                assertTrue(rows.size > 1)
                assertTrue(complete.getValue("snapshot_complete").jsonPrimitive.boolean)
                assertEquals(rows.size, complete.getValue("registry_count").jsonPrimitive.int)
                assertEquals(rows.size, complete.getValue("matched_count").jsonPrimitive.int)
                val ids = rows.map { it.getValue("id").jsonPrimitive.content }
                assertEquals(ids.sorted().distinct(), ids)
                val byId = rows.associateBy { it.getValue("id").jsonPrimitive.content }
                for (row in rows) {
                    assertFalse("label" in row || "description" in row || "usage" in row)
                    assertNotNull(row.getValue("native_usage").jsonPrimitive.intOrNull)
                    if (row.getValue("custom").jsonPrimitive.boolean) {
                        for (flag in listOf("enabled", "enabled_while_spectating", "enabled_while_in_cutscene"))
                            assertNotNull(row.getValue(flag).jsonPrimitive.booleanOrNull)
                    }
                    row["effective_bindings"]?.let { effective ->
                        val owner = byId.getValue(row.getValue("binding_owner").jsonPrimitive.content)
                        assertEquals(owner.getValue("bindings"), effective)
                    }
                    assertTrue(row.getValue("binding_owner").jsonPrimitive.content in ids)
                    val effective = row["effective_bindings"] ?: row.getValue("bindings")
                    assertEquals(effective.jsonArray.any {
                        it.jsonObject.getValue("type").jsonPrimitive.content in listOf(
                            "Keyboard",
                            "MouseButton",
                            "MouseWheel"
                        )
                    }, row.getValue("has_binding").jsonPrimitive.boolean)
                    for (field in listOf("bindings", "effective_bindings")) {
                        val bindings = row[field]?.jsonArray ?: continue
                        assertTrue(bindings.size <= 2)
                        for (value in bindings) {
                            val binding = value.jsonObject
                            assertTrue(
                                binding.getValue("slot").jsonPrimitive.content in
                                        listOf("keyboard_mouse_primary", "keyboard_mouse_secondary")
                            )
                            val type = binding.getValue("type").jsonPrimitive.content
                            assertTrue(
                                type in listOf(
                                    "Keyboard",
                                    "MouseButton",
                                    "MouseWheel"
                                ) || type.startsWith("Unknown(")
                            )
                            assertNotNull(binding.getValue("native_code").jsonPrimitive.intOrNull)
                            assertNotNull(binding.getValue("native_modifier_bits").jsonPrimitive.intOrNull)
                        }
                    }
                }
                val page = client.tool("input_bindings", buildJsonObject {
                    put("offset", 1)
                    put("limit", 1)
                }).toolValue()
                assertEquals(listOf(rows[1]), page.getValue("controls").jsonArray.map { it.jsonObject })
                val selected = ids.first()
                val missing = "factorio-mcp-nonexistent-control"
                assertFalse(missing in ids)
                val filtered = client.tool("input_bindings", buildJsonObject {
                    putJsonArray("ids") {
                        add(selected)
                        add(missing)
                    }
                    put("search", selected.uppercase())
                }).toolValue()
                assertEquals(listOf(rows.first()), filtered.getValue("controls").jsonArray.map { it.jsonObject })
                assertEquals(
                    listOf(missing),
                    filtered.getValue("missing_ids").jsonArray.map { it.jsonPrimitive.content })
                println(
                    "factorio-mcp input bindings acceptance: ${rows.size} controls; " +
                        "binding types=${
                            rows.flatMap { it.getValue("bindings").jsonArray }.map {
                                it.jsonObject.getValue("type").jsonPrimitive.content
                            }.toSet()
                        }"
                )
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
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
