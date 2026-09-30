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
import platform.posix._SC_PAGESIZE
import platform.posix.getenv
import platform.posix.sysconf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Select a disposable running single-player world, its endpoint and unmodified menu-toggle key. */
class GraphicsKeyHookAcceptanceTest {
    @Test
    fun validatesGraphicsAfterKeyboardHookAdmission() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val url = environment("FACTORIO_MCP_ACCEPTANCE_URL")
        val key = environment("FACTORIO_MCP_TEST_MENU_KEY")
        require(pid > 0)
        withTimeout(600_000) {
            ProcessHandle(pid).use { process ->
                val (metadata, bias) = process.withExecutable { image ->
                    FrameContextMetadata.resolve(image) to image.loadBias(
                        process.executableMappings(),
                        sysconf(_SC_PAGESIZE)
                    )
                }
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

                suspend fun paused() = client.tool("status").toolValue().getValue("paused").jsonPrimitive.boolean
                try {
                    client.initialize()
                    client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                    attached = true
                    assertFalse(paused())
                    toggle()
                    assertEquals(true, paused())
                    process.withExecutable { image -> metadata.verifyLoaded(image, process, bias) }
                    println("Verified loaded graphics metadata while the native keyboard hook is installed")
                    toggle()
                    assertFalse(paused())
                    assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                    attached = false
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
}
