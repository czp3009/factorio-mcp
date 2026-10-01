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

/** Opt-in history observation in an explicitly selected disposable world, not multiplayer CRC acceptance. */
class ChatReadAcceptanceTest {
    @Test
    fun observesSubmittedTextAndAdvancesCursor() = runBlocking {
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
                val before = client.tool("chat_read").toolValue()
                assertEquals("local_console_history", before.getValue("observation").jsonPrimitive.content)
                val sent = client.tool("chat_send", buildJsonObject { put("text", message) }).toolValue()
                assertEquals("completed", sent.getValue("dispatch").jsonPrimitive.content)
                val after = client.tool("chat_read", buildJsonObject {
                    put("after", before.getValue("cursor"))
                    put("limit", 128)
                }).toolValue()
                val matching = after.getValue("messages").jsonArray.map { it.jsonObject }.filter {
                    it.getValue("raw").jsonPrimitive.content.contains(message)
                }
                assertEquals(1, matching.size, "Submitted original text must appear exactly once in new observations")
                val row = matching.single()
                assertFalse(row.getValue("raw_truncated").jsonPrimitive.boolean)
                assertFalse(row.getValue("text_truncated").jsonPrimitive.boolean)
                assertFalse(after.getValue("history_lost").jsonPrimitive.boolean)
                val repeated = client.tool("chat_read", buildJsonObject {
                    put("after", after.getValue("cursor"))
                }).toolValue()
                assertTrue(repeated.getValue("messages").jsonArray.none {
                    it.jsonObject.getValue("id") == row.getValue("id")
                }, "Unchanged history must not emit the observed record again")
                println("factorio-mcp chat history: original message observed once, cursor advanced, repeated read did not duplicate it")
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
