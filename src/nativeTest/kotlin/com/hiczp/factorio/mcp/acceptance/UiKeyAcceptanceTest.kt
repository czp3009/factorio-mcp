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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Opt-in normal-pump key gestures. Select a disposable, already visible search field. */
class UiKeyAcceptanceTest {
    @Test
    fun observesSelectedAndFocusedKeyEffectsAndReleasedModifiers() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val url = environment("FACTORIO_MCP_ACCEPTANCE_URL")
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val selector = Json.parseToJsonElement(environment("FACTORIO_MCP_TEST_TEXT_SELECTOR")).jsonObject
        withTimeout(600_000) {
            val client = McpHttpClient(url)
            var attached = false
            var original: String? = null
            suspend fun read(): String {
                val nodes = client.tool("ui_read", buildJsonObject { put("selector", selector) })
                    .toolValue().getValue("nodes").jsonArray
                val node = nodes.single { it.jsonObject["matched"]?.jsonPrimitive?.boolean == true }.jsonObject
                check(node["text"] != JsonNull) { "Selected text accessor is unavailable" }
                return node["text"]?.jsonPrimitive?.content ?: ""
            }

            suspend fun replace(text: String) {
                val result = client.tool("ui_action", buildJsonObject {
                    put("action", "set_text")
                    put("selector", selector)
                    put("text", text)
                }).toolValue()
                assertEquals("completed", result.getValue("dispatch").jsonPrimitive.content)
            }

            suspend fun press(key: String, modifiers: List<String> = emptyList(), selected: Boolean = false) {
                val result = client.tool("ui_action", buildJsonObject {
                    put("action", "press_key")
                    put("key", key)
                    if (selected) put("selector", selector)
                    if (modifiers.isNotEmpty()) put("modifiers", JsonArray(modifiers.map(::JsonPrimitive)))
                }).toolValue()
                assertEquals("completed", result.getValue("dispatch").jsonPrimitive.content)
            }
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                original = read()
                replace("ab")
                press("BACKSPACE", selected = true)
                assertEquals("a", read())
                println("factorio-mcp UI keys: selected BACKSPACE observed")
                replace("ab")
                press("HOME")
                press("DELETE")
                assertEquals("b", read())
                println("factorio-mcp UI keys: current-focus HOME and DELETE observed")
                replace("abcd")
                press("A", listOf("control"))
                press("BACKSPACE")
                assertEquals("", read())
                println("factorio-mcp UI keys: Ctrl+A and BACKSPACE observed")
                val modifiers = listOf("control", "shift", "alt")
                for (mask in 1..7) {
                    replace("ab")
                    press("RIGHT", modifiers.filterIndexed { index, _ -> mask and (1 shl index) != 0 })
                    press("END")
                    press("BACKSPACE")
                    assertEquals(
                        "a",
                        read(),
                        "Plain editing after modifier chord $mask did not produce the expected text"
                    )
                }
                replace(checkNotNull(original))
                assertEquals(original, read())
                original = null
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
                println("factorio-mcp UI keys: selected/current focus, editing keys and modifier chords observed; field restored and detached")
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
