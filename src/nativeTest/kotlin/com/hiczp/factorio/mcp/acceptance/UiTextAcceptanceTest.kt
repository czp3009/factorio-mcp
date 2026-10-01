@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Opt-in text editing through the public HTTP contract. Choose a disposable search field, not a saved game setting. */
class UiTextAcceptanceTest {
    @Test
    fun replacesUnicodeAndEmptyTextThenRestoresTheField() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val url = environment("FACTORIO_MCP_ACCEPTANCE_URL")
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val selector = Json.parseToJsonElement(environment("FACTORIO_MCP_TEST_TEXT_SELECTOR")).jsonObject
        fun path(name: String) = getenv(name)?.toKString()?.let {
            Json.parseToJsonElement(it).jsonArray.map { value -> value.jsonPrimitive.content }
        }.orEmpty()

        val open = path("FACTORIO_MCP_TEST_TEXT_OPEN")
        val close = path("FACTORIO_MCP_TEST_TEXT_CLOSE")
        withTimeout(600_000) {
            val client = McpHttpClient(url)
            var attached = false
            var original: String? = null
            suspend fun read(): String? {
                val nodes = client.tool("ui_read", buildJsonObject { put("selector", selector) })
                    .toolValue().getValue("nodes").jsonArray
                val node = nodes.singleOrNull { it.jsonObject["matched"]?.jsonPrimitive?.boolean == true }
                    ?.jsonObject ?: return null
                val text = node["text"]
                check(text != JsonNull) { "Selected text accessor is unavailable" }
                return text?.jsonPrimitive?.content ?: ""
            }

            suspend fun replace(value: String) {
                val result = client.tool("ui_action", buildJsonObject {
                    put("action", "set_text")
                    put("selector", selector)
                    put("text", value)
                }).toolValue()
                assertEquals("completed", result.getValue("dispatch").jsonPrimitive.content)
            }

            suspend fun navigate(labels: List<String>) {
                for (label in labels) {
                    val result = client.tool("ui_action", buildJsonObject {
                        put("action", "click")
                        putJsonObject("selector") {
                            putJsonArray("path") {
                                addJsonObject {
                                    put("axis", "descendant")
                                    putJsonObject("match") {
                                        put("native_type", "agui::TextButton")
                                        put("text", label)
                                    }
                                }
                            }
                        }
                    }).toolValue()
                    assertEquals("completed", result.getValue("dispatch").jsonPrimitive.content)
                }
            }
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                navigate(open)
                while (original == null) {
                    original = read()
                    if (original == null) delay(25)
                }
                for (value in listOf("factorio-mcp text", "中文🙂", "", "replacement")) {
                    replace(value)
                    assertEquals(value, read(), "Text dispatch did not produce the independently observed value")
                }
                replace(checkNotNull(original))
                assertEquals(original, read())
                original = null
                navigate(close)
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
                println("factorio-mcp UI text: ASCII, Unicode, replacement and clearing observed independently; original restored and detached")
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (attached) {
                            try {
                                original?.let { replace(it) }
                            } finally {
                                client.tool("detach").toolValue()
                            }
                        }
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }
}
