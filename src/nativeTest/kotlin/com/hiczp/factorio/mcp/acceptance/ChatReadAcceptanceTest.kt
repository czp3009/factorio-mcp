@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.validateChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv

/**
 * Opt-in history observation in an explicitly selected disposable world, not multiplayer CRC
 * acceptance.
 */
class ChatReadAcceptanceTest {
    @Test
    fun observesSubmittedTextAndAdvancesNativeTickOffset() = runBlocking {
        fun environment(name: String) =
            checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
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
                assertEquals(
                    "local_console_history",
                    before.getValue("observation").jsonPrimitive.content,
                )
                val sent =
                    client.tool("chat_send", buildJsonObject { put("text", message) }).toolValue()
                assertEquals("completed", sent.getValue("dispatch").jsonPrimitive.content)
                val after =
                    client
                        .tool(
                            "chat_read",
                            buildJsonObject {
                                put("offset", before.getValue("next_offset"))
                                put("limit", 128)
                                // Submission completion precedes authoritative multiplayer
                                // delivery.
                                put("timeout", 30)
                            },
                        )
                        .toolValue()
                val matching =
                    after
                        .getValue("messages")
                        .jsonArray
                        .map { it.jsonObject }
                        .filter { it.getValue("raw").jsonPrimitive.content.contains(message) }
                // A scenario may echo the message in additional native records. Preserve every
                // record rather than treating equal or overlapping text as an adapter duplicate.
                assertTrue(
                    matching.isNotEmpty(),
                    "Submitted original text must appear in new observations",
                )
                val observedOffsets = matching.map { it.getValue("offset") }.toSet()
                assertEquals(
                    matching.size,
                    observedOffsets.size,
                    "Native records need distinct paging boundaries",
                )
                matching.forEach { row ->
                    assertFalse(row.getValue("raw_truncated").jsonPrimitive.boolean)
                    assertFalse(row.getValue("text_truncated").jsonPrimitive.boolean)
                }
                assertFalse(after.getValue("history_lost").jsonPrimitive.boolean)
                val repeated =
                    client
                        .tool(
                            "chat_read",
                            buildJsonObject { put("offset", after.getValue("next_offset")) },
                        )
                        .toolValue()
                assertTrue(
                    repeated.getValue("messages").jsonArray.none {
                        it.jsonObject.getValue("offset") in observedOffsets
                    },
                    "Unchanged history must not emit the observed record again",
                )
                val offset = repeated.getValue("next_offset")
                val expiredStart = TimeSource.Monotonic.markNow()
                val expired =
                    client
                        .tool(
                            "chat_read",
                            buildJsonObject {
                                put("offset", offset)
                                put("timeout", 1)
                            },
                        )
                        .toolValue()
                assertTrue(expiredStart.elapsedNow() >= 1.seconds)
                assertTrue(
                    expired.getValue("messages").jsonArray.isEmpty(),
                    "Use a quiet disposable world",
                )
                assertEquals(offset, expired.getValue("next_offset"))
                coroutineScope {
                    val waiting = async {
                        client
                            .tool(
                                "chat_read",
                                buildJsonObject {
                                    put("offset", offset)
                                    put("timeout", 30)
                                },
                            )
                            .toolValue()
                    }
                    delay(500)
                    assertFalse(waiting.isCompleted, "Empty history must remain pending")
                    assertTrue(
                        client.tool("status").toolValue().getValue("attached").jsonPrimitive.boolean
                    )
                    val laterText = validateChatMessage("$message long-poll")
                    client.tool("chat_send", buildJsonObject { put("text", laterText) }).toolValue()
                    val later = waiting.await()
                    assertTrue(
                        later.getValue("messages").jsonArray.any {
                            it.jsonObject.getValue("raw").jsonPrimitive.content.contains(laterText)
                        }
                    )
                    val aborting = async {
                        client.tool(
                            "chat_read",
                            buildJsonObject {
                                put("offset", later.getValue("next_offset"))
                                put("timeout", 30)
                            },
                        )
                    }
                    delay(500)
                    assertFalse(aborting.isCompleted)
                    client.tool("detach").toolValue()
                    attached = false
                    val aborted = aborting.await()
                    assertTrue(aborted.getValue("isError").jsonPrimitive.boolean)
                    assertTrue(
                        aborted.toString().contains("detach requested"),
                        "Live caller must receive terminal abort",
                    )
                }
                println(
                    "factorio-mcp chat history: original repeated contents, native paging boundaries, deadline expiration, concurrent send/status and terminal detach abort verified"
                )
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
