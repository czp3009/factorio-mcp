@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Explicit menu navigation only. This does not establish loaded-world or multiplayer action coverage. */
class UiClickAcceptanceTest {
    @Test
    fun opensAndClosesASelectedMenuWithoutOsInput() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val url = environment("FACTORIO_MCP_ACCEPTANCE_URL")
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val openText = environment("FACTORIO_MCP_TEST_OPEN_TEXT")
        val closeText = environment("FACTORIO_MCP_TEST_CLOSE_TEXT")
        fun selector(text: String) = buildJsonObject {
            putJsonArray("path") {
                addJsonObject {
                    put("axis", "descendant")
                    putJsonObject("match") {
                        put("native_type", "agui::TextButton")
                        put("text", text)
                    }
                }
            }
        }
        withTimeout(600_000) {
            val client = McpHttpClient(url)
            var attached = false
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                suspend fun waitForButton(text: String) {
                    while (true) {
                        val nodes = client.tool("ui_read", buildJsonObject { put("selector", selector(text)) })
                            .toolValue().getValue("nodes").jsonArray
                        if (nodes.any { it.jsonObject["text"]?.jsonPrimitive?.content == text }) return
                        delay(25)
                    }
                }

                suspend fun click(text: String, modifiers: List<String> = emptyList()) {
                    waitForButton(text)
                    val result = client.tool("ui_action", buildJsonObject {
                        put("action", "click")
                        put("selector", selector(text))
                        putJsonArray("modifiers") { modifiers.forEach { add(it) } }
                    }).toolValue()
                    assertEquals("completed", result.getValue("dispatch").jsonPrimitive.content)
                }
                repeat(2) {
                    click(openText)
                    click(closeText)
                    waitForButton(openText)
                    assertTrue(client.tool("status").toolValue().getValue("attached").jsonPrimitive.boolean)
                }
                for (mask in 1..7) {
                    val modifiers =
                        listOf("control", "shift", "alt").filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                    click(openText, modifiers)
                    click(closeText, modifiers)
                    waitForButton(openText)
                }
                click(openText)
                click(closeText)
                waitForButton(openText)
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
                println("factorio-mcp UI click: plain and all seven modifier chords, effects observed separately, final plain round trip and detach completed")
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
