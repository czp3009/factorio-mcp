@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.validateChatMessage
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
import kotlin.test.assertTrue

/** Opt-in single submission to an explicitly selected disposable world; verify delivery separately. */
class ChatSendAcceptanceTest {
    @Test
    fun submitsOriginalMessageAndRejectsCommandsWithoutLosingAttachment() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val url = environment("FACTORIO_MCP_ACCEPTANCE_URL")
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val message = validateChatMessage(environment("FACTORIO_MCP_TEST_CHAT_MESSAGE"))
        withTimeout(600_000) {
            val client = McpHttpClient(url)
            var attached = false
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                for (invalid in listOf(
                    "/help",
                    "  /help",
                    "line\nbreak",
                    "line\rbreak",
                    "nul\u0000byte",
                    " ",
                    "x".repeat(4097)
                )) {
                    assertTrue(
                        client.tool("chat_send", buildJsonObject { put("text", invalid) })
                            .getValue("isError").jsonPrimitive.boolean
                    )
                }
                val result = client.tool("chat_send", buildJsonObject { put("text", message) }).toolValue()
                assertEquals("completed", result.getValue("dispatch").jsonPrimitive.content)
                val status = client.tool("status").toolValue()
                assertTrue(status.getValue("attached").jsonPrimitive.boolean)
                assertEquals(pid, status.getValue("pid").jsonPrimitive.int)
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
                println("factorio-mcp chat: invalid messages rejected, one original message dispatched, attachment retained and detached; delivery requires separate observation")
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
