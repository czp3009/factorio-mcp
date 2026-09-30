@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Select a disposable, running single-player world and its unmodified menu-toggle key explicitly. */
class PauseStatusAcceptanceTest {
    @Test
    fun observesNativePauseAcrossMenuRoundTrips() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val url = environment("FACTORIO_MCP_ACCEPTANCE_URL")
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val key = environment("FACTORIO_MCP_TEST_MENU_KEY")
        withTimeout(600_000) {
            val client = McpHttpClient(url)
            var attached = false
            var opened = false
            suspend fun toggle() {
                assertEquals("completed", client.tool("ui_action", buildJsonObject {
                    put("action", "press_key")
                    put("key", key)
                }).toolValue().getValue("dispatch").jsonPrimitive.content)
                opened = !opened
            }

            suspend fun observe(paused: Boolean) {
                val status = client.tool("status").toolValue()
                assertEquals(paused, status.getValue("paused").jsonPrimitive.boolean)
                assertEquals(if (paused) "paused" else "in_game", status.getValue("state").jsonPrimitive.content)
            }
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                observe(false)
                repeat(2) {
                    toggle()
                    observe(true)
                    toggle()
                    observe(false)
                }
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
                println("factorio-mcp pause status: running/paused/running observed twice through the SDK; detached")
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (attached) {
                            try {
                                if (opened) toggle()
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
